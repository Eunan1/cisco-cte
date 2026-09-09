# relay

A client/server message relay over TCP, in plain Java 21 — no framework.

Clients register with a name, send addressed messages to each other, and explicitly
acknowledge them. Messages for an offline client are retained and delivered when it
reconnects; a message that was delivered but never acknowledged is delivered again.

Design reasoning is in **[APPROACH.md](APPROACH.md)**. The wire format is in
**[docs/PROTOCOL.md](docs/PROTOCOL.md)**.

---

## Prerequisites

| Path | Needs |
|---|---|
| **Jar** | Java 21 or newer. Maven is *not* required — use the bundled `./mvnw` |
| **Docker** | Docker only. No JDK, no Maven |

Java 21 is a hard floor: the code uses virtual threads, sealed interfaces and record
patterns.

## Build and test

```bash
./mvnw clean verify
```

Runs 55 tests and produces the artifact. On Windows use `mvnw.cmd`.

## The artifact

```
target/relay-1.0.0.jar        ~2.3 MB
```

A shaded uber jar: Jackson is bundled inside and `Main-Class` is set, so it runs with no
classpath. Both the server and the client are the same jar, chosen by subcommand.

> `target/original-relay-1.0.0.jar` also appears — that is the pre-shade jar kept by the
> shade plugin. It is not the artifact.

## Run

```bash
java -jar target/relay-1.0.0.jar server          # start the relay
java -jar target/relay-1.0.0.jar client alice    # connect as "alice"
```

The client is a four-command terminal REPL:

```
send <to> <text...>    send a message
ack <messageId>        acknowledge one you were delivered
help
quit
```

It does **not** acknowledge automatically — that is deliberate, and
[APPROACH.md](APPROACH.md) explains why.

### With Docker

Needs only Docker — no JDK, no Maven.

```bash
docker build -t relay:latest .
docker run -p 9090:9090 relay:latest
```

`-p 9090:9090` publishes the port to the host, so clients run normally from the commands
above and connect to `localhost:9090` without knowing the server is containerised. That is
the intended shape: **containerise the server, not the demo.**

<details>
<summary>If the host has no Java at all, containerise the clients too</summary>

Clients then need a user-defined network so they can resolve the server by name:

```bash
docker network create relay-demo
docker run -d --network relay-demo --name relay-server -p 9090:9090 relay:latest server
docker run --rm -it --network relay-demo -e RELAY_HOST=relay-server relay:latest client alice
```

- `-it` is **required** — without a TTY and stdin the REPL reads end-of-file and exits at once.
- `RELAY_HOST` tells the client where to connect; the server always binds `0.0.0.0`.
- Avoid `--network host` — it behaves differently on macOS and Windows.
- Omit `--rm` on the server if you want `docker logs relay-server` to work after stopping it.

</details>

Shutdown is graceful because the `ENTRYPOINT` is in exec form, so the JVM is PID 1 and
receives `SIGTERM` directly:

```bash
time docker stop <container>     # well under a second
```

### A guided walkthrough

**[docs/DEMO.md](docs/DEMO.md)** is a nine-step script that exercises all seven
requirements in about ninety seconds, including offline retention and redelivery of
unacknowledged messages.

---

## Configuration

Everything is an environment variable. The server prints its effective configuration on
startup, so what is in force is always visible.

| Variable | Default | Controls | On breach |
|---|---|---|---|
| `RELAY_PORT` | `9090` | Listen port (server) / connect port (client) | — |
| `RELAY_HOST` | `localhost` | Host the **client** connects to. The server always binds `0.0.0.0` | — |
| `RELAY_MAX_FRAME_BYTES` | `65536` | Largest JSON body on the wire, prefix excluded | `FRAME_TOO_LARGE`, **connection closed** |
| `RELAY_MAX_PAYLOAD_BYTES` | `32768` | Largest message payload, in UTF-8 bytes | `PAYLOAD_TOO_LARGE`, connection survives |
| `RELAY_MAX_MAILBOX_MESSAGES` | `1000` | Retained per identity — pending **plus** unacknowledged | `MAILBOX_FULL` to the sender |
| `RELAY_MAX_CONNECTIONS` | `256` | Concurrent connections | `CONNECTION_LIMIT_REACHED`, then closed |
| `RELAY_OUTBOUND_QUEUE_CAPACITY` | `256` | Frames buffered per connection | That connection is dropped; its mailbox survives |
| `RELAY_SHUTDOWN_TIMEOUT_MS` | `5000` | How long shutdown waits for connection threads | Remaining sockets are force-closed |

An unset or unparseable value falls back to the default. The server prints the effective
values at startup, so what is in force is never a guess.

### Timeouts

There is exactly **one** timeout, and that is deliberate:

| | |
|---|---|
| `RELAY_SHUTDOWN_TIMEOUT_MS` | How long shutdown waits for connection threads before force-closing |
| **No read timeout** | An idle connection is normal — a client may sit for hours waiting to receive |
| **No acknowledgement timeout** | Redelivery is triggered by **reconnect**, not by a timer. A client that stays connected and never acknowledges holds a message in flight indefinitely. This is the main qualifier on "at-least-once" and is discussed in [APPROACH.md](APPROACH.md#delivery-semantics) |
| **No connect timeout on the client** | The OS default applies |

Adding an acknowledgement timeout with bounded retries is the first item in
[next steps](APPROACH.md#next-steps).

Note the asymmetry between the two size limits: a **frame** violation closes the connection
because the byte stream can no longer be trusted, while a **payload** violation is a clean
rejection because the frame itself parsed correctly.

```bash
RELAY_PORT=9999 RELAY_MAX_MAILBOX_MESSAGES=50 java -jar target/relay-1.0.0.jar server
```

## Server lifecycle

| Event | What happens |
|---|---|
| **Start** | Binds `0.0.0.0:$RELAY_PORT`, prints the effective config, accepts connections |
| **Client disconnects** | The connection is released; the identity, its mailbox and any unacknowledged messages are retained |
| **Client reconnects** | Registering the same name reattaches to the same session and delivers the backlog |
| **Ctrl-C / `SIGTERM` / `docker stop`** | A JVM shutdown hook runs the graceful sequence below |

Graceful shutdown, in order:

1. Refuse new work
2. Close the server socket, which stops the accept loop
3. Send `SHUTDOWN` to every connected client
4. Close each connection, letting its writer flush what is queued
5. Await the connection threads up to `RELAY_SHUTDOWN_TIMEOUT_MS`, then force-close

State is **in memory only** — a restart loses every queued message. See
[APPROACH.md](APPROACH.md#known-limitations).

## Dependencies

One at runtime: `com.fasterxml.jackson.core:jackson-databind` for JSON. Everything else —
framing, registration, mailboxes, delivery, retry, acknowledgement — is in this repository.
JUnit 5 is test-scope only and is not in the artifact.

---

## Layout

```
src/main/java/com/eunangavin/relay/
├── Main.java                     entry point; server | client subcommand
├── Log.java                      timestamped stdout logging
├── config/RelayConfig.java       every bound, with environment overrides
├── protocol/                     the wire format - no I/O, no threads
│   ├── Frame.java                sealed interface; all 10 frame types nested
│   ├── FrameCodec.java           length-prefixed framing; the only class that sees bytes
│   ├── ErrorCode.java            every way the service says no
│   └── ProtocolException.java    a fault carrying an ErrorCode
├── net/                          sockets and threads
│   ├── RelayServer.java          bind, accept, connection limit, shutdown
│   └── Connection.java           reader thread, writer thread, bounded outbound queue
├── session/                      identity and state - never imports java.net
│   ├── ClientRegistry.java       identities, registration, takeover
│   ├── ClientSession.java        identity outliving the connection; the delivery pump
│   ├── Mailbox.java              bounded; pending queue + unacknowledged map
│   ├── Message.java
│   ├── ClientConnection.java     the seam that keeps sockets out of this package
│   └── RelayService.java         frame dispatch
└── client/
    ├── RelayClient.java          the client library; no console I/O
    └── RelayCli.java             the four-command REPL
```

Dependencies point one way: `net → session → protocol`. The `session` package never
imports `java.net`, which is what lets the mailbox and delivery logic be tested without
sockets.

## Tests

```bash
./mvnw test                                  # all 55
./mvnw test -Dtest=DeliveryTest              # one class
```

Deterministic and bounded: every test binds port `0` for an OS-assigned port, and no test
uses `Thread.sleep` for synchronisation. What they do and do not cover is discussed in
[APPROACH.md](APPROACH.md#testing).

## Documentation

| | |
|---|---|
| [APPROACH.md](APPROACH.md) | Architecture, protocol, concurrency, delivery semantics, trade-offs, limitations |
| [docs/PROTOCOL.md](docs/PROTOCOL.md) | Wire format, framing, and why TCP needs it |
| [docs/DEMO.md](docs/DEMO.md) | The nine-step walkthrough |
