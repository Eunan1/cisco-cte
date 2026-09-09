package com.eunangavin.relay.net;

import com.eunangavin.relay.Log;
import com.eunangavin.relay.config.RelayConfig;
import com.eunangavin.relay.protocol.ErrorCode;
import com.eunangavin.relay.protocol.Frame;
import com.eunangavin.relay.protocol.FrameCodec;
import com.eunangavin.relay.protocol.ProtocolException;
import com.eunangavin.relay.session.RelayService;

import java.io.DataOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Binds a port, accepts connections, and shuts down predictably.
 *
 * The acceptor runs on a platform thread — there is exactly one and it lives for the
 * process, so a virtual thread would buy nothing.
 * Every connection gets two virtual threads from {@link Executors#newVirtualThreadPerTaskExecutor()},
 * which is what makes thread-per-connection affordable: a virtual thread's stack lives on the heap and the JVM
 * unmounts it from its carrier while it blocks, so a parked reader holds no OS thread.
 */
public final class RelayServer implements AutoCloseable {

    private final RelayConfig config;
    private final RelayService service;
    private final FrameCodec codec;

    private final ExecutorService connectionThreads = Executors.newVirtualThreadPerTaskExecutor();
    private final Set<Connection> connections = ConcurrentHashMap.newKeySet();
    private final AtomicInteger activeConnections = new AtomicInteger();
    private final AtomicLong nextConnectionId = new AtomicLong(1);

    private volatile ServerSocket serverSocket;
    private volatile Thread acceptor;
    private volatile boolean running;
    private volatile boolean shutdownWasClean;

    public RelayServer(RelayConfig config, RelayService service) {
        this.config = config;
        this.service = service;
        this.codec = new FrameCodec(config.maxFrameBytes());
    }

    // ------------------------------------------------------------------ lifecycle

    public void start() throws IOException {
        ServerSocket socket = new ServerSocket();
        socket.setReuseAddress(true);
        // 0.0.0.0, not localhost - otherwise nothing outside a container could reach it.
        socket.bind(new InetSocketAddress("0.0.0.0", config.port()));

        this.serverSocket = socket;
        this.running = true;
        this.acceptor = Thread.ofPlatform().name("relay-acceptor").start(this::acceptLoop);

        Log.info("listening on port %d", port());
    }

    /** The actual bound port. Differs from config when port 0 asked the OS to choose. */
    public int port() {
        ServerSocket socket = serverSocket;
        if (socket == null) {
            throw new IllegalStateException("server has not been started");
        }
        return socket.getLocalPort();
    }

    public int connectionCount() {
        return activeConnections.get();
    }

    /**
     * Whether the last {@link #close()} saw every connection thread finish inside the
     * shutdown timeout.
     *
     * This is the only workable thread-leak signal here: connection threads are
     * virtual, and virtual threads do not appear in
     * {@link Thread#getAllStackTraces()}, so a test cannot enumerate them. What it can do is
     * ask the executor whether every task it was given actually returned which is exactly
     * the question "did any reader or writer survive?".
     *
     * False means a reader or writer was still parked when the timeout expired, which in
     * practice means the unblock asymmetry broke: interrupt for the writer, socket close for
     * the reader.
     */
    public boolean shutdownWasClean() {
        return shutdownWasClean;
    }

    /**
     * Stops the server. Order matters, and each step depends on the previous one:
     *
     * Refuse new work, so nothing registers while we are tearing down.
     * Close the ServerSocket. There is no "stop accepting" call, closing is what makes the acceptor's
     *  blocked {@code accept()} throw, which is its exit route.
     * Tell connected clients why, and give the writers a bounded moment to flush it.
     * Close every connection and await the threads.
     * Report anything that had to be forced.
     *
     */
    @Override
    public void close() {
        if (!running) {
            return;
        }
        running = false;
        service.shuttingDown();

        try {
            serverSocket.close();   // unblocks accept()
        } catch (IOException ignored) {
            // Nothing useful to do; we are stopping anyway.
        }

        Log.info("shutting down: notifying %d connection(s)", connections.size());

        // Queue the notice on every connection first, then close them. Connection.close is
        // graceful — it waits for its writer to flush what is already queued — so doing all
        // the offers up front lets the writers work in parallel rather than one at a time.
        connections.forEach(c -> c.offer(new Frame.Shutdown("server shutting down")));
        connections.forEach(c -> c.close("server shutdown"));

        connectionThreads.shutdownNow();
        boolean clean;
        try {
            clean = connectionThreads.awaitTermination(
                    config.shutdownTimeout().toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            clean = false;
        }
        this.shutdownWasClean = clean;

        Log.info("shutdown complete%s", clean ? "" : " (timed out waiting for connection threads)");
    }

    // ------------------------------------------------------------------ accepting

    private void acceptLoop() {
        while (running) {
            Socket socket;
            try {
                socket = serverSocket.accept();
            } catch (IOException e) {
                // The designed shutdown path: close() makes this throw. Only a throw while
                // we still believe we are running is a genuine fault.
                if (running) {
                    Log.warn("accept failed: %s", e.getMessage());
                }
                return;
            }
            accept(socket);
        }
    }

    private void accept(Socket socket) {
        // incrementAndGet then check, so two simultaneous accepts cannot both slip past a
        // size() read. Decrement immediately if we are over.
        if (activeConnections.incrementAndGet() > config.maxConnections()) {
            activeConnections.decrementAndGet();
            rejectOverLimit(socket);
            return;
        }

        String label = "conn-" + nextConnectionId.getAndIncrement();
        try {
            Connection connection = new Connection(
                    label, socket, codec, config.outboundQueueCapacity(), this::connectionClosed);
            connections.add(connection);
            Log.info("%s accepted from %s (%d/%d connections)",
                    label, socket.getRemoteSocketAddress(),
                    activeConnections.get(), config.maxConnections());

            connectionThreads.execute(() -> connection.runReader(service));
            connectionThreads.execute(connection::runWriter);
        } catch (IOException e) {
            activeConnections.decrementAndGet();
            Log.warn("%s could not be set up: %s", label, e.getMessage());
            closeQuietly(socket);
        }
    }

    /**
     * Called once per connection, from {@link Connection#close}, on whichever thread got
     * there first. {@code Connection} has no equals/hashCode override, so removal is by
     * identity — which is what we want.
     */
    private void connectionClosed(Connection connection) {
        if (connections.remove(connection)) {
            activeConnections.decrementAndGet();
        }
    }

    /**
     * We cannot refuse a TCP connection at the accept level and still say why, so we accept
     * it, write the reason directly (no threads started for a connection we are discarding),
     * and close.
     */
    private void rejectOverLimit(Socket socket) {
        Log.warn("connection limit %d reached; rejecting %s",
                config.maxConnections(), socket.getRemoteSocketAddress());
        try (socket) {
            var out = new DataOutputStream(socket.getOutputStream());
            codec.writeFrame(out, new Frame.Error(
                    ErrorCode.CONNECTION_LIMIT_REACHED,
                    "server is at its connection limit of " + config.maxConnections()));
        } catch (IOException | ProtocolException e) {
            // The client may already be gone. Nothing to do.
        }
    }

    private static void closeQuietly(Socket socket) {
        try {
            socket.close();
        } catch (IOException ignored) {
            // Nothing useful to do.
        }
    }
}
