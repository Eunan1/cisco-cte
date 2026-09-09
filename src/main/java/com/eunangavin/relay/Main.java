package com.eunangavin.relay;

import com.eunangavin.relay.client.RelayCli;
import com.eunangavin.relay.config.RelayConfig;
import com.eunangavin.relay.net.RelayServer;
import com.eunangavin.relay.session.ClientRegistry;
import com.eunangavin.relay.session.RelayService;

import java.util.concurrent.CountDownLatch;

/**
 * Entry point. One artifact, dispatched by subcommand:
 *
 * <pre>
 *   java -jar relay.jar server
 *   java -jar relay.jar client alice      (STORY-5)
 * </pre>
 *
 * <p>One jar rather than two keeps "the artifact produced" a single clean answer in the
 * README, and makes the demo's two commands nearly identical — which matters when typing
 * in front of an audience.
 *
 * <p>Wiring is explicit constructor calls. With no framework there is no dependency
 * injection and no auto-configuration, so the entire object graph is visible here in a
 * dozen lines. At this size that is a readability gain, not a cost.
 */
public final class Main {

    public static void main(String[] args) throws Exception {
        String mode = args.length > 0 ? args[0] : "server";
        switch (mode) {
            case "server" -> runServer();
            case "client" -> {
                if (args.length < 2) {
                    System.err.println("usage: java -jar relay.jar client <name>");
                    System.exit(2);
                }
                RelayConfig config = RelayConfig.fromEnvironment();
                RelayCli.run(args[1], RelayConfig.clientHost(), config.port());
            }
            default -> {
                System.err.println("usage: java -jar relay.jar [server|client <name>]");
                System.exit(2);
            }
        }
    }

    private static void runServer() throws Exception {
        RelayConfig config = RelayConfig.fromEnvironment();
        printConfig(config);

        RelayService service = new RelayService(
                new ClientRegistry(config.maxMailboxMessages()), config);
        RelayServer server = new RelayServer(config, service);
        server.start();

        // Ctrl-C, and `docker stop` in STORY-6, arrive as SIGTERM. Without this hook the
        // JVM exits immediately and none of the graceful shutdown in RelayServer.close
        // ever runs - no SHUTDOWN frames, no draining, no thread cleanup.
        CountDownLatch stopped = new CountDownLatch(1);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            server.close();
            stopped.countDown();
        }, "relay-shutdown"));

        // Park the main thread. The acceptor and the connection threads do the work.
        stopped.await();
    }

    /**
     * Prints the effective configuration on boot.
     *
     * <p>Worth the eight lines: it answers "how are ports, timeouts and limits controlled?"
     * before anyone asks, and during the demo it puts every bound on screen where the
     * interviewer can see them.
     */
    private static void printConfig(RelayConfig config) {
        Log.info("relay starting with effective configuration:");
        Log.info("  RELAY_PORT                     = %d", config.port());
        Log.info("  RELAY_HOST (client only)       = %s", RelayConfig.clientHost());
        Log.info("  RELAY_MAX_FRAME_BYTES          = %d", config.maxFrameBytes());
        Log.info("  RELAY_MAX_PAYLOAD_BYTES        = %d", config.maxPayloadBytes());
        Log.info("  RELAY_MAX_MAILBOX_MESSAGES     = %d", config.maxMailboxMessages());
        Log.info("  RELAY_MAX_CONNECTIONS          = %d", config.maxConnections());
        Log.info("  RELAY_OUTBOUND_QUEUE_CAPACITY  = %d", config.outboundQueueCapacity());
        Log.info("  RELAY_SHUTDOWN_TIMEOUT_MS      = %d", config.shutdownTimeout().toMillis());
    }
}
