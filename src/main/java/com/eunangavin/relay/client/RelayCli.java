package com.eunangavin.relay.client;

import com.eunangavin.relay.protocol.Frame;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/**
 * A terminal client. Four commands, no prompt, no history — deliberately minimal.
 *
 * <p><b>On scope:</b> the brief lists "a user interface" among the things not required. This
 * is not that. A length-prefixed binary protocol cannot be driven by hand — {@code netcat}
 * is useless against it — so without something like this there is no way to exercise the
 * server outside the test suite. It is capped at four verbs on purpose; anything more would
 * start becoming the thing the brief said not to build.
 *
 * <p><b>It does not auto-acknowledge.</b> That is the important behaviour here, for two
 * reasons. Demonstrating requirement 7 requires receiving a message and deliberately
 * <em>not</em> acking it before disconnecting. And auto-acking would be wrong anyway: it
 * would let the server discard a message the instant it reached the socket, so a client that
 * died mid-processing would lose it — at-most-once, not at-least-once. The ack means "I have
 * taken responsibility", which only the application can say.
 */
public final class RelayCli implements RelayClient.MessageListener {

    private static final String HELP =
            "  send <to> <text...>   send a message\n"
            + "  ack <messageId>       acknowledge a delivered message\n"
            + "  help                  this\n"
            + "  quit                  disconnect and exit";

    private RelayCli() {
    }

    public static void run(String clientId, String host, int port) throws IOException {
        RelayCli cli = new RelayCli();

        try (RelayClient client = RelayClient.connect(host, port, cli)) {
            Frame.Registered registered = tryRegister(client, clientId);
            if (registered == null) {
                return;
            }
            System.out.printf("<< REGISTERED as %s  (pending=%d)%n",
                    registered.clientId(), registered.pending());
            System.out.println(HELP);

            // The MAIN thread blocks here on the user; the reader thread blocks on the
            // socket. Two blocking sources, one thread each - see RelayClient's comment.
            var console = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
            String line;
            while ((line = console.readLine()) != null) {
                if (!handle(client, line.trim())) {
                    return;
                }
            }
        }
    }

    /** @return false when the user asked to quit. */
    private static boolean handle(RelayClient client, String line) {
        if (line.isEmpty()) {
            return true;
        }
        // A UTF-8 BOM survives trim() and would make the first command unrecognised.
        // Never happens when typed by hand; happens immediately if anyone pipes a script in.
        if (line.charAt(0) == '﻿') {
            line = line.substring(1);
        }
        String[] parts = line.split("\\s+", 3);
        try {
            switch (parts[0]) {
                case "quit" -> {
                    return false;
                }
                case "help" -> System.out.println(HELP);
                case "ack" -> {
                    require(parts.length >= 2, "usage: ack <messageId>");
                    System.out.printf("<< ACK_OK    %s%n", client.ack(parts[1]).messageId());
                }
                case "send" -> {
                    require(parts.length >= 3, "usage: send <to> <text...>");
                    print(client.send(parts[1], parts[2]));
                }
                default -> System.out.println("   unknown command. try 'help'");
            }
        } catch (Exception e) {
            // Never let a bad command kill the session - the demo has to survive a typo.
            System.out.println("   !! " + e.getMessage());
        }
        return true;
    }

    private static void print(Frame verdict) {
        if (verdict instanceof Frame.Accepted accepted) {
            System.out.printf("<< ACCEPTED  %s%n", accepted.messageId());
        } else if (verdict instanceof Frame.Rejected rejected) {
            System.out.printf("<< REJECTED  %s  %s - %s%n",
                    rejected.messageId(), rejected.code(), rejected.reason());
        }
    }

    private static Frame.Registered tryRegister(RelayClient client, String clientId) {
        try {
            return client.register(clientId);
        } catch (Exception e) {
            System.out.println("   !! could not register: " + e.getMessage());
            return null;
        }
    }

    private static void require(boolean condition, String usage) {
        if (!condition) {
            throw new IllegalArgumentException(usage);
        }
    }

    // ------------------------------------------------------------------ pushes

    @Override
    public void onDeliver(Frame.Deliver deliver) {
        // Print the ack command alongside it, so the demo needs no memory.
        System.out.printf("<< DELIVER   %s from %s : %s     <-- type 'ack %s'%n",
                deliver.messageId(), deliver.from(), deliver.payload(), deliver.messageId());
    }

    @Override
    public void onShutdown(Frame.Shutdown shutdown) {
        System.out.printf("<< SHUTDOWN  %s%n", shutdown.reason());
    }

    @Override
    public void onDisconnected(String reason) {
        System.out.printf("<< disconnected: %s%n", reason);
        // System.exit does NOT flush System.out, and stdout is block-buffered whenever it
        // is redirected rather than attached to a console - so without this the tail of
        // that line is lost whenever the output is piped or captured.
        System.out.flush();
        // Deliberate. The main thread is parked in readLine() and there is no way to wake
        // it - interrupts do not break console reads. For a terminal program whose only
        // purpose is this session, exiting when the connection dies is correct. In a
        // library this would be unacceptable.
        System.exit(0);
    }
}
