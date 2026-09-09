# APPROACH

How this was built and why. Operational instructions are in [README.md](README.md).

---

## Acceptance criteria

The brief's requirements, quoted, each with where it is implemented and the test that
proves it. All 55 tests pass; the suite is one command (`./mvnw test`).

### Core scenario

> **1** A client registers with a unique name or ID, and more than one client may be registered at once.
> **2** Either client can send a uniquely identified message to the other.
> **3** The service confirms whether it accepted or rejected a send.
> **4** The recipient receives the message and explicitly acknowledges it.
> **5** A recipient may disconnect. Messages sent to it while offline are retained within defined resource limits.
> **6** The recipient can reconnect and register again using the same name or ID, then receive its offline messages.
> **7** A message delivered before a disconnect but not acknowledged remains available for redelivery after reconnecting.

| # | Implemented by | Proved by |
|---|---|---|
| 1 | `ClientRegistry.register`, `RelayService.handleRegister` — `REGISTER` → `REGISTERED` | `RelayServerTest.twoClientsRegisterIndependently` |
| 2 | `RelayService.handleSend` — `SEND` carries a client-supplied `messageId` | `DeliveryTest.deliverThenAckEmptiesMailbox` |
| 3 | `ACCEPTED` / `REJECTED`, both keyed by that `messageId` | `DeliveryTest.unknownRecipientRejected`, `mailboxFullRejected` |
| 4 | `ClientSession.pump` pushes `DELIVER`; `RelayService.handleAck` answers `ACK_OK`. **No auto-ack** | `DeliveryTest.deliverThenAckEmptiesMailbox` |
| 5 | `Mailbox` is owned by `ClientSession`, not by the connection; bounded by `RELAY_MAX_MAILBOX_MESSAGES` | `DeliveryTest.offlineRecipientAccumulatesAndReceivesOnReconnect` |
| 6 | `ClientRegistry` looks up by name and reattaches the existing session | `RelayServerTest.reregisteringReattachesSameSession` |
| 7 | `ClientSession.attach` requeues the inflight map to the head of pending, in order | `DeliveryTest.unackedMessageIsRedeliveredAfterReconnect` |

### Behaviour to get right

> • Re-registering an identity after disconnect reattaches the same logical client without losing its queued messages.
> • A message is removed only after the correct recipient acknowledges it. At-least-once delivery is acceptable: an unacknowledged message may be delivered again.
> • Mailboxes, message sizes, active connections, and buffers are bounded. Define how the service reports invalid input and resource limits.
> • Define and document your behaviour for message ordering, duplicate sends, and repeated or stale acknowledgements. FIFO ordering is an optional bonus below.
> • A slow, malformed, or disconnected client does not block unrelated clients. Concurrent operations preserve correct state, and the server shuts down predictably.

| Behaviour | How | Proved by |
|---|---|---|
| Reattach without losing queued messages | Identity outlives the socket; the connection is a nullable field on the session | `ClientRegistryTest.reregisteringReattachesSameSession` |
| Removed only by the **correct recipient** | The ack lookup is scoped to the acking client's own mailbox — *structural*, not a validation check. See Delivery semantics | `DeliveryTest.ackFromWrongClientRemovesNothing` |
| Four bounds, all configurable | Mailbox, payload, connections, outbound buffer — see Reporting invalid input below | `MailboxTest.rejectsWhenFull`, `RelayServerTest.connectionLimitIsEnforced`, `DeliveryTest.oversizedPayloadRejectedConnectionSurvives`, `FrameCodecTest` |
| Ordering, duplicates, stale acks defined | See Delivery semantics | `OrderingTest`, `DeliveryTest.repeatedAndStaleAcksSucceed`, `duplicateMessageIdRejected` |
| Slow / malformed / disconnected client isolated | Per-connection bounded queue and writer thread; no shared pool | `DeliveryTest.slowRecipientDoesNotDelayUnrelatedClients`, `RelayServerTest.malformedFrameClosesOnlyThatConnection`, `ClientSessionTest.pumpReturnsConnectionWhenQueueRefuses` |
| Predictable shutdown | Five-step sequence, bounded by `RELAY_SHUTDOWN_TIMEOUT_MS` — see Connection lifecycle | `RelayServerTest.shutdownCompletesWithinTimeout` |

### Testing

> Include a small, focused set of automated tests for the documented requirements and important failure cases... Tests should be deterministic, bounded by timeouts, and runnable using one documented command.

One command: `./mvnw test`. Deterministic — every server test binds port `0`, and no test
uses `Thread.sleep` for synchronisation. What is and is not covered is in Testing below.

### Optional bonuses

| | Status |
|---|---|
| **1 — FIFO ordering**, including concurrent sends and reconnects | **Done.** Per-recipient FIFO in server-acceptance order. See Delivery semantics |
| **2 — Docker**, small reproducible image with documented commands | **Done.** Multi-stage build, ~2 commands. See [README.md](README.md) |
| **3 — Persistence** across restart | **Not done.** In-memory only; a restart loses every queued message. Design sketched in Next steps |

---

## Architecture

```
   net  ──────▶  session  ──────▶  protocol
  (sockets,     (identity,        (frames,
   threads)      state)            codec)
```

Dependencies point one way, and **`session` never imports `java.net`**. That is not
tidiness: it is what allows the mailbox, the delivery pump and the registry to be
unit-tested with no sockets, no threads and no timing. A small `ClientConnection` interface
declared in `session` and implemented by `net.Connection` is the seam.

The load-bearing split is between what dies with a socket and what outlives it:

```
  Per connection - dies with the socket      Per identity - survives it
  ┌──────────────────────────────┐          ┌────────────────────────────┐
  │ reader thread ──▶ dispatch   │─────────▶│ ClientRegistry             │
  │ [bounded outbound queue]     │◀─────────│   └─ ClientSession         │
  │ writer thread ──▶ socket     │          │        └─ Mailbox          │
  └──────────────────────────────┘          └────────────────────────────┘
```

`ClientSession` owns the mailbox; the connection is a *nullable field on it*. Every
requirement about disconnect and reconnect falls out of that one placement. Had the mailbox
hung off the connection, requirements 5, 6 and 7 would each have been a rewrite.

**No framework.** The brief permits one, but everything Spring would supply is something I
could not then claim as mine — and the relay behaviour has to be my code. Wiring is a dozen
explicit constructor calls in `Main`.

## Protocol

Length-prefixed JSON over TCP: a 4-byte big-endian body length, then a UTF-8 JSON frame.

```
+--------------------+---------------------------------+
| 4-byte length (BE) | UTF-8 JSON body (length bytes)  |
+--------------------+---------------------------------+
```

TCP delivers an ordered stream of *bytes*; it says nothing about where one application
message ends and the next begins, because it splits on network boundaries (MTU, congestion
window, Nagle) rather than application ones. Framing is what recovers those boundaries.

**Length prefix rather than a delimiter**, because the size bound then becomes checkable
*before* any allocation: the limit is an `if` on an integer already read. With
newline-delimited JSON I would have to read and count, letting a client that never sends a
delimiter drive work proportional to its garbage. The cost is real — length-prefixed frames
cannot be typed into `netcat`, which is why there is a small CLI.

Three client operations, two server pushes:

| Client → server | Server → client |
|---|---|
| `REGISTER` → `REGISTERED` / `ERROR` | `DELIVER` (unsolicited) |
| `SEND` → `ACCEPTED` / `REJECTED` | `SHUTDOWN` (unsolicited) |
| `ACK` → `ACK_OK` / `ERROR` | |

Frames are a sealed interface with all ten types nested as records, so handler switches are
exhaustive at compile time — adding a frame type turns every unhandled site into a compile
error. Full detail in [docs/PROTOCOL.md](docs/PROTOCOL.md).

## Connection lifecycle

> The brief: *"Document the protocol and connection lifecycle in APPROACH.md, including how
> a client reconnects and identifies itself, how acknowledgements work, and what happens
> when a connection closes."*

**Connection model:** one long-lived TCP connection per client, full duplex. The same
socket carries `SEND` upward and `DELIVER` downward — no second connection, no polling.

```
   connect ──▶ REGISTER ──▶ REGISTERED ──▶ active ──▶ close
      │            │                          │          │
      │            └── anything else here     │          └── session and mailbox SURVIVE
      │                → ERROR(NOT_REGISTERED)│
      └── over maxConnections                 └── SEND/ACK up, DELIVER/SHUTDOWN down
          → ERROR(CONNECTION_LIMIT_REACHED), closed
```

**1 — Connect.** The acceptor checks the connection limit *before* starting any threads. Over
the limit, the socket gets `ERROR(CONNECTION_LIMIT_REACHED)` and is closed immediately.

**2 — Register.** The client sends `REGISTER{clientId}`. Until that succeeds the connection has
no identity, and any other frame is answered `ERROR(NOT_REGISTERED)`. On success the server
replies `REGISTERED{clientId, pending}` — `pending` is how many messages are already waiting,
which is what makes reconnect observable to the client rather than silent.

**3 — Active.** Both directions run concurrently until the connection closes.

### How a client identifies itself on reconnect

**It sends the same `clientId` again. That is the entire mechanism** — no token, no session
id, no credential. The registry is a map keyed by name; if a session exists under that name,
the new connection attaches to it and inherits the mailbox.

This is a deliberate, documented limitation, not an oversight: **any client can claim any
identity.** Authentication is out of scope for the exercise, and inventing a half-authentication
scheme would be worse than naming the gap.

**Takeover.** If the identity already has a live connection, **the newcomer wins**: the old
connection is sent `ERROR(ALREADY_REGISTERED)` and closed, and the session rebinds to the new
one. The alternative — reject the newcomer — is worse, because a half-open TCP connection is
undetectable until a write fails. A client whose network dropped would be locked out of its own
identity until the server happened to notice, which may be never.

On attach, anything sitting in the inflight map is **requeued to the head of pending, in order**,
so redelivery precedes new traffic and FIFO survives the reconnect.

### How acknowledgements work

`DELIVER{messageId, from, payload}` → client → `ACK{messageId}` → `ACK_OK{messageId}`.

- **Only an `ACK` removes a message.** Delivery moves it from pending to inflight; nothing else empties inflight except a matching ack or a fresh delivery cycle.
- **Acks are explicit, never automatic.** The CLI requires you to type `ack <id>`. Auto-acking would make requirement 7 untestable and would turn at-least-once into at-most-once.
- **Ownership is structural.** The lookup is scoped to the acking client's own mailbox, so a client acking another client's id cannot reach that mailbox at all.
- **Repeated and stale acks are idempotent** and still answer `ACK_OK`. At-least-once *guarantees* double acks, so erroring would punish a client for behaviour the delivery guarantee forces on it.

### What happens when a connection closes

Three ways in — clean client close (`readFrame` returns `null` at end of stream), a broken
socket or stream-level protocol fault, or server shutdown. **All three take the same path:**

1. The connection detaches from its session — under the session lock, and only if it is still the *current* connection (a takeover must not let the departing socket detach its replacement).
2. Inflight messages are requeued to the head of pending, in order.
3. The writer is stopped with a poison pill, so **frames already queued are still flushed** — a takeover notice or an `ERROR` must reach the peer before the socket goes.
4. The socket closes.

**The session, the mailbox, and every unacknowledged message survive.** Nothing accepted is
lost by a disconnect; it is retained until acknowledged or until the process ends.

**Server shutdown** is a JVM shutdown hook (Ctrl-C, `SIGTERM`, `docker stop`), in five steps:
refuse new work → close the server socket, stopping the accept loop → send `SHUTDOWN` to every
connected client → close each connection, letting its writer flush → await the threads up to
`RELAY_SHUTDOWN_TIMEOUT_MS`, then force-close. Clients are told, rather than discovering a dead
socket.

## Reporting invalid input and resource limits

> The brief: *"Define how the service reports invalid input and resource limits."*

Two shapes of failure, and the difference is which one the client can correlate:

| | `REJECTED{messageId, code, reason}` | `ERROR{code, reason}` |
|---|---|---|
| Scope | one `SEND` | the connection |
| Correlatable | yes, by `messageId` | no |
| Used when | the send was understood and declined | there is no message to answer about |

Every code is an enum value, so the wire vocabulary is fixed at compile time and a typo cannot
ship. The grouping maps to how the server reacts:

| Code | Meaning | Reported as | Connection |
|---|---|---|---|
| `NOT_REGISTERED` | An identity-requiring frame arrived before `REGISTER` | `ERROR` | survives |
| `ALREADY_REGISTERED` | Second `REGISTER`, or this identity was taken over | `ERROR` | survives / closed on takeover |
| `UNKNOWN_RECIPIENT` | No session for that name | `REJECTED` | survives |
| `DUPLICATE_MESSAGE_ID` | That id is already live in the recipient's mailbox | `REJECTED` | survives |
| `MAILBOX_FULL` | Recipient at `RELAY_MAX_MAILBOX_MESSAGES` | `REJECTED` **to the sender** | survives |
| `PAYLOAD_TOO_LARGE` | Payload over `RELAY_MAX_PAYLOAD_BYTES`, measured in UTF-8 **bytes** | `REJECTED` | survives |
| `CONNECTION_LIMIT_REACHED` | Server at `RELAY_MAX_CONNECTIONS` | `ERROR` | **closed** |
| `SLOW_CONSUMER` | Outbound queue full; this client is too far behind | `ERROR` | **closed**, mailbox survives |
| `FRAME_TOO_LARGE` | Declared length negative or over `RELAY_MAX_FRAME_BYTES` | `ERROR` | **closed** |
| `MALFORMED_FRAME` | Length prefix valid, body unparseable or unusable | `ERROR` | **closed** |
| `SERVER_SHUTTING_DOWN` | Work arrived during shutdown | `ERROR` | closing |

**The asymmetry is the point.** A *request-level* fault means the frame parsed correctly and we
simply declined its contents — the byte stream is still trustworthy, so the connection carries
on. A *stream-level* fault means we no longer know where the next frame begins; continuing would
read garbage forever, so the connection must close. `PAYLOAD_TOO_LARGE` and `FRAME_TOO_LARGE`
look like the same kind of error and are deliberately handled in opposite ways for exactly this
reason.

**Four bounds, all configurable, all documented in [README.md](README.md):** mailbox depth
(pending *plus* inflight), payload size, concurrent connections, and per-connection outbound
buffer. A fifth — maximum frame size — guards the codec before any allocation happens.

## State model

Each identity has one `Mailbox` holding two structures:

```
  pending: ArrayDeque<Message>     never sent
  inflight: LinkedHashMap<Id,Msg>  sent, awaiting ACK
```

Two structures rather than one, because a single list with a cursor breaks the moment an
out-of-order acknowledgement removes from the middle — which is normal, since delivery is
pipelined. `LinkedHashMap` gives O(1) ack lookup *and* insertion order for requeueing;
`ArrayDeque` gives O(1) at both ends.

The bound counts **pending plus inflight**. Inflight has to count: it is retained state, and
a client that never acknowledges would otherwise grow the mailbox without limit while the
pending count looked healthy.

A full mailbox **rejects the newest** rather than evicting the oldest. Evicting would
silently discard a message the sender was already told was `ACCEPTED`, breaking the only
promise that frame makes.

## Concurrency model

| Thread | Count | Blocks on |
|---|---|---|
| Acceptor | 1, platform | `accept()` |
| Reader | 1 per connection, **virtual** | `readFully` on the socket |
| Writer | 1 per connection, **virtual** | `outboundQueue.take()` |

**No shared worker pool and no shared dispatcher** — a shared pool is precisely how one slow
client starves everyone else.

Two threads per connection rather than one is the decision that makes the brief's isolation
requirement true. If delivery wrote straight to the recipient's socket, it would do so on
the *sender's* thread; a recipient that stopped reading would fill its kernel buffer, TCP's
window would close, `write()` would block, and Alice would be stalled by Bob's problem.
Instead, delivery calls a non-blocking `offer` on the recipient's bounded queue and returns.

When that queue fills, the connection is **dropped** — not blocked (which reinstates the
problem) and not silently discarded (which breaks the `ACCEPTED` promise). The session and
mailbox outlive the socket, so reconnecting recovers everything.

Virtual threads are what make thread-per-connection affordable: a blocked virtual thread is
unmounted from its carrier and costs a heap object, not an OS thread. On Java 17 this would
have been an NIO selector loop with hand-rolled partial-read reassembly and `OP_WRITE`
interest management — several hundred lines of well-known-to-be-buggy infrastructure with
nothing to do with the relay.

State is guarded by a `ReentrantLock` per session, never `synchronized`: on Java 21 a
virtual thread blocking inside `synchronized` pins its carrier. (JEP 491 removed that in
Java 24, but the artifact targets 21 and may run on it.)

Two rules the code follows and that are easy to get wrong:

- **Never write to a socket while holding a session lock.** `ClientSession.pump` and `attach` *return* a connection for the caller to close, rather than closing it themselves.
- **Never take two session locks.** Only the recipient's is acquired, so two clients sending to each other cannot deadlock.

## Delivery semantics

| | |
|---|---|
| **Delivery** | At-least-once, **with redelivery on reconnect** |
| **Ordering** | Per-recipient FIFO, in **server-acceptance order** |
| **`ACCEPTED` means** | The message is in the recipient's mailbox — not delivered, not read |
| **Duplicate sends** | Rejected while the id is live in the target mailbox |
| **Repeated / stale acks** | Idempotent no-ops that still return `ACK_OK` |
| **Ack from the wrong client** | Removes nothing — *structurally*, not by validation |
| **Ack ordering** | Not guaranteed; delivery order is |

**The qualifier on at-least-once matters.** There is no acknowledgement timeout, so
redelivery is triggered by reconnect and nothing else. A client that stays connected and
never acknowledges holds a message in flight indefinitely.

**Ordering means server-acceptance order**, not sender order. Two clients on different
machines share no clock, so sender order is unobservable and claiming it would be inventing
something. Acceptance order is well-defined because enqueueing happens under the recipient's
session lock — the lock protects the mailbox *and* makes "first" meaningful. Not guaranteed:
any interleaving between concurrent senders, ordering across different recipients, or ack
ordering.

**Acknowledgement ownership is structural.** The ack lookup is scoped to the acking client's
own mailbox, so a client acknowledging another client's message id never holds a reference
to that mailbox and cannot remove anything. That is stronger than an ownership check, which
would leave a `remove(id)` on a shared map one refactor from a serious bug. The cost, stated
plainly: a wrong-recipient ack is then indistinguishable from a stale one, so both return
`ACK_OK`.

**Stale acks must be idempotent.** At-least-once delivery *guarantees* double
acknowledgements — ack, connection drops before it lands, reconnect, redelivered, ack again.
Erroring would punish a client for behaviour the delivery guarantee forces on it.

## Testing

**41 test methods, 55 cases** — two methods are parameterised: one round-trips all ten
frame types, one checks six invalid client ids. One command: `./mvnw test`.
Deterministic: every server test binds port `0`, and no test uses `Thread.sleep` for
synchronisation — waits are on a real signal with a deadline.

| Class | Methods | Covers |
|---|---|---|
| `DeliveryTest` | 10 | The seven core requirements end to end, plus duplicates, stale acks, bounds and isolation |
| `FrameCodecTest` | 8 | Framing: round trip of all ten types, segmentation, hostile length prefixes, forward compatibility |
| `RelayServerTest` | 7 | Bind, registration, takeover, connection limit, malformed-frame isolation, shutdown |
| `MailboxTest` | 5 | Queueing and requeue ordering — **no sockets** |
| `ClientRegistryTest` | 3 | Identity creation, the takeover/detach race, id validation — **no sockets** |
| `ClientSessionTest` | 3 | The delivery pump and its refusal path — **no sockets** |
| `OrderingTest` | 3 | FIFO: sequential, concurrent senders, and across reconnect |
| `RelayClientTest` | 1 | Smoke test that the client library round-trips |
| `RelayConfigTest` | 1 | The one config invariant: payload limit must not exceed frame limit |

**The suite is deliberately small.** The brief asks for *"a small, focused set"* and says
exhaustive coverage is not expected, so the rule I applied was: **prove each graded
requirement once, at the lowest level that proves it.** An earlier version had 72 methods and
88 cases; most of the excess was the same claim proved at two levels — reattachment and
takeover were tested in both `ClientRegistryTest` and `RelayServerTest`, and six tests
covered environment-variable parsing, which is not what the brief grades.

**What was cut, and what that costs.** Config parsing (5), most of the client-library tests
(4), the byte-level assertion of the length prefix, truncated-body handling, and thread-leak
checking at shutdown. The real losses worth naming: shutdown is now proved to *complete in
time* but not to *leave no threads running*, and the client's push-versus-reply routing is
exercised only indirectly. Both were judged acceptable against the brief's explicit
preference for a focused suite.

**What was kept even though it looked cuttable.** `ClientRegistryTest.evictedConnectionDisconnectDoesNotDetachSuccessor`
is the only test of the identity check in `detach` — without it, a taken-over connection
closing later would detach its own *replacement*, and nothing would notice. That is a real
race with a real guard, so it stays.

**What is not covered, and why.** Single JVM only: no multi-process behaviour, no real
network partitions, no load or soak testing, no clock manipulation (so nothing time-based).
Packaging, the container and CI have no unit tests — the test boundary stops at the JVM, and
past it verification is running the thing, which CI does on every push. Those were the right
boundaries for the size of this exercise.

**Two things worth reporting about the tests themselves.**

The definition of done for the ordering work required each new test to be *verified capable
of failing*. So I changed the mailbox to requeue at the tail instead of the head and re-ran.
**Only one test went red.** Four ordering tests passed against a deliberately broken
implementation, because in each the pending queue was empty at the moment of requeue — and
on an empty deque, head and tail are the same place. I added a test for the non-empty case,
and it is one of the five that survived the trim.

Separately, two real defects in the client were invisible to a green suite and only appeared
when I ran the program: `System.exit` does not flush `System.out` (and stdout is
block-buffered when redirected), and a UTF-8 BOM survives `trim()`. Neither is reachable
from a unit test.

## Build, run, and verification

There are two independent ways to build and run this, on purpose. The brief says the repo
*"must reproduce in the interview environment using the documented commands"* and that *"no
laptop is needed"* — so the environment is not one I control. Two paths with different
prerequisites means a missing toolchain on one side does not block reproduction.

### Path A — the jar

Needs **Java 21+**. Maven is not required; `./mvnw` downloads a pinned Maven on first use.

```bash
git clone <repo> && cd relay

./mvnw clean verify                              # build + 55 tests
java -jar target/relay-1.0.0.jar server          # terminal 1
java -jar target/relay-1.0.0.jar client alice    # terminal 2
java -jar target/relay-1.0.0.jar client bob      # terminal 3
```

The artifact is `target/relay-1.0.0.jar` — a shaded uber jar with Jackson bundled and
`Main-Class` set, so no classpath is needed and the command is identical on every platform.
Server and client are the same jar, selected by subcommand.

### Path B — Docker

Needs **only Docker**. No JDK, no Maven.

```bash
git clone <repo> && cd relay

docker build -t relay:latest .                   # multi-stage; compiles inside the image
docker run -p 9090:9090 relay:latest             # server
```

`-p 9090:9090` publishes the port to the host, so clients run normally from Path A and
connect to `localhost:9090` without knowing the server is containerised. That is the
intended shape: **containerise the server, not the demo.**

If the host has no Java at all, clients can be containerised too — at the cost of a
user-defined network so they can resolve the server by name:

```bash
docker network create relay-demo
docker run -d --network relay-demo --name relay-server -p 9090:9090 relay:latest server
docker run --rm -it --network relay-demo -e RELAY_HOST=relay-server relay:latest client alice
```

`-it` is required or the REPL reads end-of-file and exits immediately. `RELAY_HOST` exists
for exactly this case: the server always binds `0.0.0.0`, so only a client needs a target
host, which is why it is a separate lookup rather than a field on `RelayConfig`.

### What each path actually proves

The three checks are deliberately independent, and overlap less than they appear to:

| | Compiles from a clean tree | Runs the 55 tests | Needs no JDK | Automatic |
|---|---|---|---|---|
| `./mvnw verify` | ✅ | ✅ | ❌ | ❌ |
| CI (GitHub Actions) | ✅ | ✅ | ❌ | ✅ on every push |
| `docker build` | ✅ | ❌ **`-DskipTests`** | ✅ | ❌ |

The Docker build **skips the tests deliberately** — conventional, because an image build
should not fail on a flaky test and should not pay test time on every rebuild. That is
precisely the gap CI fills: it runs the full suite on a machine with none of my local
state, on every push, without anyone remembering to.

CI is not required by the brief — it lists CI as a discussion topic. It is twelve lines and
does one useful job: it is the *"verify the documented build and test commands"* step from
the brief, automated.

### Graceful shutdown under Docker

Worth verifying rather than assuming:

```bash
time docker stop relay-server     # expect: well under a second
```

The `ENTRYPOINT` is in **exec form**, so the JVM is PID 1 and receives `SIGTERM` directly.
In shell form Docker would run `/bin/sh -c "java -jar ..."`, the shell would be PID 1, and
it does not forward signals — the JVM would never hear the stop, the shutdown hook would
never run, and Docker would `SIGKILL` the container after the full grace period. The only
visible symptom is that `docker stop` takes ten seconds instead of one.

## Trade-offs

| Decision | Alternative | Why this one |
|---|---|---|
| Raw TCP | gRPC / HTTP | The exercise grades protocol design. gRPC would make framing, bounds and backpressure library behaviour rather than mine |
| Plain Java | Spring Boot | Faster to write, but the relay behaviour must be mine, and a small codebase is easier to defend |
| Length prefix | Newline-delimited JSON | Bound checkable before allocation. Cost: not drivable from `netcat` |
| JSON | Binary / protobuf | Readable in a hex dump and in logs; efficiency is irrelevant at this scale |
| Thread per connection | NIO selector | Straight-line blocking code, affordable on virtual threads |
| Drop the connection when its queue fills | Block, or drop the frame | Blocking reinstates the problem; dropping the frame breaks the `ACCEPTED` promise |
| Reject on full mailbox | Evict oldest | Evicting silently discards a message already called `ACCEPTED` |
| Takeover on re-registration | Reject the newcomer | A half-open TCP connection is undetectable until a write fails; rejecting would strand a client whose network dropped |
| In-memory state | Persistence | Bonus 3, and the largest remaining piece. See Next steps |

## Known limitations

- **In memory only.** A restart loses every queued message.
- **No authentication or encryption.** Any client can claim any identity.
- **Single server.** The registry is process-local by design.
- **Sessions are never reclaimed.** Registering many unique ids grows memory without bound; idle expiry is the fix.
- **Duplicate detection is bounded to live ids.** Once a message is acknowledged and evicted, its id would be accepted again as new. Unbounded dedupe history is a memory leak, so the window is deliberately bounded.
- **No acknowledgement timeout.** Redelivery happens on reconnect only.
- **Half-open connections** are detected only on the next write. A keepalive would narrow the window.
- **Payloads are UTF-8 strings**, not arbitrary bytes.
- **One operation in flight per client.** `RelayClient` matches replies by kind, not by id, which is sufficient for a REPL but would mismatch under concurrent requests. A `correlationId` on the envelope is the fix.
- **`Log` writes to stdout** with no levels or structure. A real service would use a proper logger.

## Next steps

In the order I would do them:

1. **Acknowledgement timeout** with bounded retries, then dead-lettering — removes the "at-least-once *on reconnect*" qualifier.
2. **`correlationId` on the envelope** — lets a client have several requests in flight.
3. **Bounded LRU of acknowledged ids** — closes the duplicate-detection window without unbounded memory.
4. **Idle-session expiry** — bounds the last unbounded structure.
5. **Persistence** (bonus 3) — an append-only log of accept/deliver/ack events, replayed on boot. The interesting decision is fsync policy: fsyncing under the session lock puts disk latency on the send path, which contradicts the "slow things do not block others" property the whole design is built on.
6. **Keepalive** to detect half-open connections sooner.
7. **Metrics** on queue depth, mailbox size and redelivery counts.

## On scope

**This went past the two-hour guide.** Held strictly to it, I would have shipped the core
(framing, sessions, mailbox, delivery, acknowledgement) and documented FIFO, the client and
Docker as next steps.

I kept going because being able to *demonstrate* offline retention and redelivery explains
the design better than describing it does, and because the two bonuses I took were cheap
given the earlier decisions — the mailbox was a deque from the first commit specifically so
FIFO would be nearly free.

The work was done spec-first: each story was specified, implemented, then reconciled against
what was actually built, with deviations recorded. Those specs are not in this repository —
they run to several thousand lines and are learning material rather than a deliverable — but
the decisions they produced are recorded here, which is the document the brief names.

## AI-tool usage

> **Rewrite this section yourself before submitting.** It must describe what actually
> happened. The draft below is a starting point, not a claim to adopt unexamined.

I used Claude (Claude Code) throughout, as a pair rather than as an autocomplete.

**How.** I chose the language, runtime and transport, and the direction of each story. Work
proceeded spec-first: a specification was written and reviewed before implementation, the
implementation followed it, and the spec was then reconciled against the code with every
deviation recorded. I reviewed and validated each story before moving to the next, and
pushed back on several proposals — a plural holder class for the frame records, a
disproportionate test suite, and whether a REPL was in scope at all given the brief lists
"a user interface" as not required.

**Verification.** Beyond the tests: the ordering implementation was checked by mutation
(requeue at the tail instead of the head), which showed four ordering tests passing against
a broken implementation and led to an extra test. A claim about thread interruption that had
already been written into the code comments was checked with a standalone probe and turned
out to be wrong for virtual threads, so the comments and documentation were corrected. The
demo was verified by running the program, which surfaced two defects the test suite could
not see.

**Responsibility.** I can explain and modify every part of this. The design decisions —
identity outliving the connection, two threads per connection, structural ack ownership,
acceptance-order FIFO — are ones I can defend and would make again.
