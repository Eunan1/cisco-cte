# STORY-2 — Server, connections, and identity

| | |
|---|---|
| **Epic** | EPIC-1 — Core relay |
| **Priority** | P0 |
| **Points** | 8 |
| **Status** | Done — validated |
| **Depends on** | STORY-1 (the codec this story feeds sockets into) |

## 1. Goal

A running TCP server. It binds, accepts connections up to a limit, gives each one a reader
thread, a writer thread and a bounded outbound queue, and shuts down predictably. On top of
that, a `ClientRegistry` of `ClientSession`s in which **identity outlives the connection**:
`REGISTER` claims a name, dropping the socket detaches the connection but keeps the
session, and registering the same name again reattaches to that same session.

After this story you can start the server, connect two clients, register them, kill one,
reconnect it, and watch it rebind to the same session object — satisfying **requirements 1
and 6** of the brief.

**There is no mailbox yet.** Nothing is queued, nothing is delivered, nothing is
acknowledged. `SEND` and `ACK` are not handled. That is STORY-3, and keeping it out means
this story's failures are all lifecycle failures rather than a tangle of lifecycle and
delivery.

## 2. Why this matters

This story contains the two decisions the whole submission rests on.

**The identity/connection seam.** If the mailbox ends up hanging off the connection,
every requirement about disconnect and reconnect becomes a rewrite rather than a patch. Get
`ClientSession` owning the state and `Connection` being a nullable field on it, and
requirements 5, 6 and 7 fall out almost for free in STORY-3.

**The per-connection bounded outbound queue.** The brief requires that "a slow, malformed,
or disconnected client does not block unrelated clients". That property is won or lost
here, not in the testing story. Once a shared dispatcher or a direct socket write from
another client's thread exists, it is very hard to remove.

Everything else in this story is plumbing. These two are the interview.

## 3. Concepts & Theory

### 3.1 Thread-per-connection, and why it is affordable now

Each connection gets **two** threads:

| Thread | Blocks on | Why it exists |
|---|---|---|
| Reader | `codec.readFrame(in)` → ultimately `readFully` | Decode inbound frames and dispatch them |
| Writer | `outboundQueue.take()` | Encode outbound frames and write them to the socket |

The reason for **two** rather than one is the whole point of the design. If delivery wrote
straight to the recipient's socket, it would do so on whichever thread happened to be
delivering — in STORY-3 that is the *sender's* reader thread. If the recipient has stopped
reading, its kernel receive buffer fills, TCP's flow-control window closes, our `write()`
blocks, and now Alice's connection is stalled by Bob's problem. That is precisely the
failure the brief calls out.

Separating them means delivery never touches a socket. It hands a frame to the recipient's
queue and returns immediately. Backpressure is contained inside that one connection.

On why we can afford thread-per-connection at all, and what Java 17 would have forced us
to write instead, see [ADR-001](../adr/ADR-001-language-and-transport.md) — the NIO
selector alternative requires hand-rolled partial-read reassembly and partial-write
interest management, several hundred lines of well-known-to-be-buggy infrastructure that
has nothing to do with the relay.

### 3.2 Bounded queues are the backpressure mechanism

```
  delivery ──offer()──▶ [ ArrayBlockingQueue, capacity 256 ] ──take()──▶ writer ──▶ socket
                                      │
                        full? ────────┘
```

Three possible policies when the queue is full, and the choice is graded:

| Policy | Consequence |
|---|---|
| **Block** the offering thread | Reintroduces exactly the problem the queue was meant to solve — the slow client now stalls whoever is delivering to it |
| **Drop the frame** | Silently loses a message we may already have told the sender was `ACCEPTED`, breaking that promise |
| **Drop the connection** ✅ | A client 256 frames behind is not keeping up. Close it. Its session and mailbox survive, so nothing is lost — it reconnects and gets everything |

We take the third. Say the reasoning out loud: *"dropping the connection is honest
backpressure — the client is told, nothing is lost because the mailbox outlives the socket,
and no other client is affected."*

### 3.3 Unblocking the two parked threads

> **Corrected after measurement.** An earlier draft of this section repeated the common
> claim that interrupting can never free a thread blocked in socket I/O. That is true for a
> *platform* thread and **false for the virtual threads this server actually uses**. The
> wrong version is recorded here because the correction is the more interesting answer.

To shut a connection down, two threads parked in different ways must both be freed.

| Thread | Parked on | Freed by |
|---|---|---|
| Writer | `queue.take()` | `interrupt()` — `take()` is interruptible and throws `InterruptedException` |
| Reader | a socket read inside `readFully` | `socket.close()` — throws `SocketException` |

**The nuance worth knowing.** Conventional Java wisdom is that a thread blocked in
`java.io` socket I/O ignores interrupts, so only closing the socket will free it. Measured
directly on JDK 21:

```
platform thread    interrupt -> still blocked
virtual thread     interrupt -> UNBLOCKED (SocketException)
```

Virtual threads use the **NIO-backed socket implementation**, so a socket read on a virtual
thread *is* interruptible. Since every reader here is a virtual thread, an interrupt would
in fact free it.

We still close the socket, for two reasons worth stating:

1. It is correct regardless of thread type — the code does not silently depend on every reader being virtual.
2. Freeing the thread is not the only goal. We want the **file descriptor released**, and an interrupt alone does not do that.

This is a better interview answer than the textbook one, precisely because it contradicts
it: *"the usual claim is that interrupt cannot break a socket read — that is true for
platform threads, but virtual threads are NIO-backed and do unblock. I checked."*

### 3.3a Closing without losing the last frame

> **This section was written after implementation.** The first design interrupted the
> writer as soon as `close()` was called, and it failed three tests. It is kept here
> because the wrong version is instructive.

Almost every close in this server *follows a frame we actually want delivered*: the
takeover notice, a `MALFORMED_FRAME` error, the `SHUTDOWN` broadcast. Interrupt the writer
immediately and all of those are still sitting in the queue when the socket dies, so the
peer sees an unexplained EOF instead of the reason.

**The obvious fix does not work.** Waiting for the outbound queue to become empty seems
right and is not:

```
  writer:   Frame f = outbound.take();   ← the frame LEAVES the queue here
            codec.writeFrame(out, f);    ← but is only ON THE WIRE here
```

Between those two lines the queue reports empty while the write is still in flight. Close
the socket in that window and the frame is lost anyway. **Queue emptiness is the wrong
signal.**

The right signal is the writer itself. `close()` therefore:

1. sets `closing`, so `offer` stops accepting new frames;
2. enqueues a **poison pill** — a private `Frame` instance compared by *identity*, never
   transmitted, so no fake operation has to be added to the sealed protocol;
3. waits on a `CountDownLatch` that the writer counts down as it exits, bounded by a grace
   period;
4. calls `closeNow()` regardless, once drained or once the grace expires.

The writer sees the pill only after everything queued ahead of it has been written, so its
exit is proof the socket got the lot.

Two consequences worth knowing:

- **A full queue cannot accept the pill.** The grace expires and we force the close. That is exactly right for a slow consumer — it is already too far behind to wait for.
- **The writer must never wait on itself.** Its own `finally` block calls `closeNow()`, not `close()`, or it would block forever awaiting a latch only it can count down.

### 3.4 `accept()` throwing is the normal shutdown path

There is no "stop accepting" call on `ServerSocket`. You close it, and the thread blocked in
`accept()` throws `SocketException`. That is the *designed* exit route, not a failure —
catch it, check whether shutdown was requested, and exit quietly. Logging it at ERROR makes
every clean shutdown look like a crash.

### 3.5 The takeover race

Two decisions that interact, and the interaction is where the bug lives.

**Decision one: a new registration wins.** If `alice` registers while a connection already
holds that name, we evict the old one rather than rejecting the new one. The reason is that
a half-open TCP connection is *undetectable* until a write to it fails — so rejecting would
permanently strand a client whose network dropped, with the server insisting it is still
connected. The trade-off, which should be documented: two clients genuinely sharing a name
will fight over it.

**Decision two: detach must be identity-checked.**

```
  t0  connection A registers as "alice"          session.connection = A
  t1  connection B registers as "alice"          session.connection = B, A is evicted
  t2  A's reader loop exits and calls detach     ← MUST NOT null out B
```

A naive `session.setConnection(null)` on disconnect destroys a perfectly healthy
connection that arrived moments earlier. The fix is one line — detach only if the session's
current connection *is* the one detaching — but the bug is invisible in any test that does
not reconnect quickly.

### 3.6 `ReentrantLock`, not `synchronized`

Per the project convention. On Java 21 a virtual thread that blocks inside a `synchronized`
block **pins** its carrier platform thread, and enough pinned carriers starve the scheduler.
`ReentrantLock` does not pin. (JEP 491 removed the limitation in Java 24 — worth knowing the
nuance, since the artifact targets 21 and may well run on it. `ReentrantLock` also wins
independently for `tryLock` and interruptibility.)

## 4. Design & Approach

### The dependency rule

```
   net  ──────▶  session  ──────▶  protocol
  (sockets,     (identity,        (frames,
   threads)      state)            codec)
```

Dependencies point one way only. **`session` must never import `java.net`.** That is not
aesthetics — it is what lets STORY-3 unit-test `Mailbox` and `ClientSession` with no
sockets, which is the difference between fast deterministic tests and slow flaky ones.

To make it hold, `session` declares a small interface that `net.Connection` implements:

```java
package com.eunangavin.relay.session;

/** What the relay core needs from a connection. Deliberately not a socket. */
public interface ClientConnection {
    /** @return false if the outbound queue is full; the caller decides what that means. */
    boolean offer(Frame frame);
    void close(String reason);
    ClientSession session();          // null until REGISTER succeeds
    void bind(ClientSession session);
    String label();                   // for logging only
}
```

Alternative considered: let `session` depend on `net` directly and drop the interface. It is
fewer lines, but every session test would then need a real socket. The interface costs
about eight lines and buys the entire test strategy for STORY-3.

### Shutdown sequence

Order matters; each step depends on the previous one having happened.

1. Flip state to `SHUTTING_DOWN` so new registrations are refused.
2. `serverSocket.close()` → the acceptor's `accept()` throws and that thread exits.
3. For each open connection: `offer(Shutdown)` so clients learn why, then close.
4. `executor.shutdown()` and `awaitTermination(config.shutdownTimeout())`.
5. Force-close anything still open, and log what had to be forced.

A `Runtime.getRuntime().addShutdownHook(...)` wires this to Ctrl-C — and to `docker stop`
in STORY-6, which is what makes the exec-form `ENTRYPOINT` matter.

### Connection limit

You cannot refuse a TCP connection at the accept level and still say why. So: accept it,
check the count, and if we are at the limit send `CONNECTION_LIMIT_REACHED` and close. The
client gets a reason rather than an unexplained reset.

## 5. Files to create / modify

| Path | Purpose |
|---|---|
| `session/ClientConnection.java` | The interface above — keeps `session` free of `java.net` |
| `session/ClientSession.java` | Identity, `ReentrantLock`, nullable current connection, attach/detach |
| `session/ClientRegistry.java` | `ConcurrentHashMap<String, ClientSession>`, registration and takeover |
| `session/RelayService.java` | Frame dispatch: the exhaustive switch. Handles `REGISTER`; rejects the rest for now |
| `net/RelayServer.java` | Bind, accept loop, connection limit, lifecycle |
| `net/Connection.java` | Socket, reader thread, writer thread, bounded outbound queue; implements `ClientConnection` |
| `Main.java` | `server` subcommand; prints effective config on boot; shutdown hook |
| `Log.java` | Timestamped stdout logging. The server terminal is the demo's observability window, and that wants short aligned lines — not worth a dependency, not worth a framework's default format |
| `protocol/ErrorCode.java` | **Modify:** add `SLOW_CONSUMER`, and `NOT_IMPLEMENTED` as temporary scaffolding so `SEND`/`ACK` answer honestly. **Delete `NOT_IMPLEMENTED` in STORY-3.** |
| `test/.../TestClient.java` | Test-scope client: socket + codec + `expect(type)`. Synchronous reads bounded by `SO_TIMEOUT` — no background thread to coordinate with. Reused heavily in STORY-3 |
| `test/.../RelayServerTest.java` | Lifecycle, limits, malformed input, isolation, shutdown |
| `test/.../ClientRegistryTest.java` | Reattach and takeover, with a fake `ClientConnection` — no sockets |

## 6. Implementation

### 6.1 `Connection`

```java
public final class Connection implements ClientConnection {

    private final Socket socket;
    private final DataInputStream in;
    private final DataOutputStream out;
    private final FrameCodec codec;
    private final BlockingQueue<Frame> outbound;   // ArrayBlockingQueue, bounded

    /** Sentinel telling the writer to stop. Identity-compared, never transmitted. */
    private static final Frame POISON = new Frame.Shutdown("__writer-stop__");
    private static final Duration WRITER_DRAIN_GRACE = Duration.ofMillis(500);

    private final AtomicBoolean closing = new AtomicBoolean(false);  // no new frames
    private final AtomicBoolean closed  = new AtomicBoolean(false);  // socket torn down
    private final CountDownLatch writerDone = new CountDownLatch(1); // the drain signal

    private volatile ClientSession session;        // null until REGISTER
    private volatile Thread writerThread;

    @Override
    public boolean offer(Frame frame) {
        // Non-blocking by design: a full queue must never stall the caller, because the
        // caller in STORY-3 is another client's reader thread.
        return !closing.get() && outbound.offer(frame);
    }

    void runReader(RelayService service) {
        try {
            Frame frame;
            while ((frame = codec.readFrame(in)) != null) {   // null == clean close
                service.onFrame(this, frame);
            }
        } catch (ProtocolException e) {
            // Stream-level fault: tell them, then close - we can no longer trust the stream.
            offer(new Frame.Error(e.code(), e.getMessage()));
            } catch (IOException e) {
            // Socket died, or we closed it ourselves during shutdown. Not an error.
        } finally {
            service.onDisconnect(this);
            close("reader ended");
        }
    }

    void runWriter() {
        writerThread = Thread.currentThread();
        try {
            while (true) {
                Frame frame = outbound.take();     // interruptible
                if (frame == POISON) return;       // identity, deliberately not equality;
                                                   // everything ahead of it is written
                codec.writeFrame(out, frame);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();    // restore the flag, then exit
        } catch (IOException e) {
            // Peer went away mid-write.
        } finally {
            writerDone.countDown();
            closeNow("writer ended");   // NOT close() - that awaits a latch only we can count
        }
    }

    /** Graceful: stop accepting, let the writer flush, then tear down. See section 3.3a. */
    @Override
    public void close(String reason) {
        if (closing.compareAndSet(false, true)) {
            outbound.offer(POISON);     // best effort; a full queue means we force below
        }
        if (Thread.currentThread() != writerThread) {     // the writer must not wait on itself
            try {
                writerDone.await(WRITER_DRAIN_GRACE.toMillis(), TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        closeNow(reason);
    }

    /** Hard close. Anything still queued is lost. */
    void closeNow(String reason) {
        if (!closed.compareAndSet(false, true)) return;   // idempotent: both threads reach it
        closing.set(true);
        Thread writer = writerThread;
        if (writer != null && writer != Thread.currentThread()) {
            writer.interrupt();                           // unblocks queue.take()
        }
        try { socket.close(); } catch (IOException ignored) { }   // unblocks the reader
        onClosed.accept(this);                            // deregisters from RelayServer
    }
}
```

Three things to notice, and they are the ones an interviewer could reasonably dig into:

- **`offer` guards on `closing`, not `closed`.** Once a close has begun we must stop taking new frames, even though the socket is still up while the writer drains.
- **Two close methods, not one.** `close` is graceful and is what everything calls; `closeNow` is the hard path, used by the writer's own `finally` and once the grace expires.
- **The unblock asymmetry** lives in `closeNow`: interrupt the writer, close the socket for the reader (§3.3).

### 6.2 `ClientSession` and reattachment

```java
public final class ClientSession {

    private final String clientId;
    private final ReentrantLock lock = new ReentrantLock();
    private ClientConnection connection;          // null when offline. Guarded by lock.

    /** @return the connection that was evicted, or null. Caller closes it outside the lock. */
    ClientConnection attach(ClientConnection incoming) {
        lock.lock();
        try {
            ClientConnection evicted = this.connection;   // takeover: new one wins
            this.connection = incoming;
            return evicted;
        } finally {
            lock.unlock();
        }
    }

    void detach(ClientConnection leaving) {
        lock.lock();
        try {
            // Identity check - see 3.5. Without it, a slow disconnect from an evicted
            // connection silently kills the connection that replaced it.
            if (this.connection == leaving) {
                this.connection = null;
            }
        } finally {
            lock.unlock();
        }
    }
}
```

Close the evicted connection **outside** the lock. Closing does socket I/O, and holding a
lock across I/O is how one slow socket becomes everyone's problem.

### 6.3 `RelayService` dispatch

```java
public void onFrame(ClientConnection conn, Frame frame) {
    switch (frame) {                              // exhaustive: Frame is sealed
        case Frame.Register r  -> handleRegister(conn, r);
        case Frame.Send s      -> conn.offer(notYet(s.messageId()));   // STORY-3
        case Frame.Ack a       -> conn.offer(notYet(a.messageId()));   // STORY-3
        // Server-to-client frames arriving inbound are a client bug.
        case Frame.Registered r -> reject(conn);
        case Frame.Accepted a   -> reject(conn);
        // ... every remaining case
    }
}
```

No `default` branch — that is the point of the sealed interface. When STORY-3 fills in
`Send` and `Ack`, the compiler has already guaranteed nothing was forgotten.

Every reply goes through one private helper rather than calling `offer` directly:

```java
private void sendOrDrop(ClientConnection connection, Frame frame) {
    if (!connection.offer(frame)) {
        connection.close("outbound queue full: " + ErrorCode.SLOW_CONSUMER);
    }
}
```

The slow-consumer policy lives **here, not in `Connection`** — it is a relay decision, not
a transport one, and keeping it in the service leaves `ClientConnection` at five methods.

### 6.4 `TestClient`

Worth building properly; STORY-3 leans on it.

```java
final class TestClient implements AutoCloseable {
    static TestClient connect(int port) { ... }
    void send(Frame frame) { ... }
    /** Blocks up to timeout for the next frame. Fails the test rather than hanging. */
    Frame expect(Duration timeout) { ... }
    <T extends Frame> T expect(Class<T> type, Duration timeout) { ... }
}
```

## 7. Gotchas & pitfalls

1. **Free both parked threads, and know why each works** (§3.3). Interrupt frees the writer from `take()`; closing the socket frees the reader *and* releases the descriptor. Note that interrupt would also free a **virtual** reader — the textbook "interrupt cannot break socket I/O" applies to platform threads only.
2. **Detach without an identity check** destroys the connection that just took over (§3.5).
3. **`accept()` throwing `SocketException` is the normal shutdown path**, not an error (§3.4).
4. **`close()` must be idempotent.** Reader and writer both call it, possibly at once. `AtomicBoolean.compareAndSet` guards the body.
4a. **Interrupting the writer immediately loses the frame you were closing to send** (§3.3a). Every close here follows a notice the peer needs.
4b. **Queue emptiness is not "everything was written."** `take()` removes a frame before the write happens, so the queue reads empty mid-write. Wait on the writer's own exit instead.
4c. **The writer must never call the graceful `close()`** from its own `finally` — it would await a latch only it can count down. `closeNow()` is the writer's exit path.
5. **Never hold the session lock across socket I/O.** Return the evicted connection from `attach` and close it outside.
6. **Bind port 0 in tests** and read back `serverSocket.getLocalPort()`. Fixed ports make CI flaky.
7. **`Thread.currentThread().interrupt()` after catching `InterruptedException`** — catching it clears the flag, and swallowing that fact hides shutdown intent from anything further up.
8. **Wrap the socket streams in `BufferedInputStream`/`BufferedOutputStream`** before the `Data*` wrappers, or every `writeInt` is its own syscall. The codec already calls `flush`.
9. **`ConcurrentHashMap.computeIfAbsent` must not do slow or re-entrant work** inside the mapping function — it holds a bin lock. Create the bare session there; do attachment after.
10. **Sessions are never reclaimed.** Registering many unique ids grows memory without bound. Accepted for the timebox — document it as a known limitation with idle expiry as the next step, and say it before they ask.
11. **`clientId` needs validation:** non-blank, length-capped, restricted charset. It becomes a map key and appears in logs.

## 8. Acceptance Criteria

- [ ] Server binds port 0 and reports the actual assigned port.
- [ ] Two clients connect concurrently and both register successfully.
- [ ] Register `alice`, drop the socket, register `alice` again → the *same* `ClientSession` instance is reattached.
- [ ] A second live connection claiming `alice` evicts the first, which receives a reason frame before closing.
- [ ] After a takeover, the evicted connection's disconnect does **not** detach the new one.
- [ ] A second `REGISTER` on one connection is rejected with `ALREADY_REGISTERED`.
- [ ] Blank, oversized, or illegal `clientId` is rejected without creating a session.
- [ ] Connection number `maxConnections + 1` receives `CONNECTION_LIMIT_REACHED` and is closed; existing connections are unaffected.
- [ ] A malformed frame closes that connection only; an unrelated client continues normally.
- [ ] A connection whose outbound queue fills is closed with `SLOW_CONSUMER`; its session survives.
- [ ] `stop()` completes within `shutdownTimeout`, connected clients receive `SHUTDOWN`, and the port is free afterwards.
- [ ] No thread leaks: after shutdown, no reader or writer threads remain.

## 9. Tests to write

Ten methods. Every one bounded by `assertTimeoutPreemptively`; no `Thread.sleep` used as
synchronisation.

| Test | Asserts |
|---|---|
| `bindsEphemeralPortAndReportsIt` | Port 0 works and `port()` returns the real one |
| `twoClientsRegisterIndependently` | Requirement 1 — independent identities coexist |
| `reregisteringReattachesSameSession` | Requirement 6 — the core of the story |
| `newConnectionTakesOverIdentity` | Old connection is told why, then closed |
| `secondRegisterOnOneConnectionIsRejected` | `ALREADY_REGISTERED`; no session for the second name |
| `invalidClientIdIsRejected` | `NOT_REGISTERED`; registry stays empty |
| `connectionLimitIsEnforced` | `CONNECTION_LIMIT_REACHED`; existing clients unaffected |
| `slowConsumerIsDroppedButSessionSurvives` | Queue fills, connection dropped, identity retained |
| `malformedFrameClosesOnlyThatConnection` | Isolation, and the server survives |
| `inboundServerFrameIsRejected` | A client sending `REGISTERED` is closed |
| `shutdownCompletesWithinTimeout` | Clients get `SHUTDOWN`, port released |

`ClientRegistryTest` adds 12 more, all against a fake `ClientConnection` — no sockets at
all, because reattachment and takeover are pure logic and deserve pure tests.

**Actual totals: 11 + 12 in this story, 51 across the suite, five consecutive clean runs.**

**Deliberately not here:** the slow-client-does-not-block-others test. It needs something
worth sending, which means a mailbox. It lands in STORY-3 and it is the most valuable test
in the suite.

## 10. Manual verification

```bash
mvn clean verify
```

Then, in three terminals:

```bash
java -jar target/relay-1.0.0.jar server
```

Expected: effective config printed (all six bounds), then `listening on 9090`.

Connect twice with `nc localhost 9090` — the length prefix means you cannot type frames by
hand, but you can confirm the server accepts, logs the connection, and logs a clean
disconnect on Ctrl-C rather than a stack trace. Then Ctrl-C the server and confirm shutdown
completes promptly and says what it closed.

## 11. Out of scope

| Not in this story | Owned by |
|---|---|
| Mailboxes, `SEND`, delivery, `ACK`, redelivery | STORY-3 |
| FIFO ordering guarantees | STORY-4 |
| `RelayClient`, the CLI, the demo | STORY-5 |
| Shaded jar, Docker, CI, README/APPROACH | STORY-6 |
| Idle-session expiry (sessions are never reclaimed) | Documented limitation; not planned |
| Keepalive / heartbeat to detect half-open connections | Documented next step; not planned |

## 11a. Deviations from the original spec

Recorded so this document matches the code. Each is a decision worth being able to defend.

| # | Spec said | Built instead | Why |
|---|---|---|---|
| 1 | `close()` interrupts the writer immediately | `close()` is graceful (poison pill + latch); `closeNow()` is the hard path | The original lost the frame it was closing to send, failing three tests. See §3.3a — this is the most interesting change and the best one to be asked about. |
| 2 | Wait for the outbound queue to empty before closing | Wait on the writer's own exit | `take()` removes a frame before writing it, so the queue reads empty mid-write. Wrong signal. |
| 3 | `Connection.closeSlowConsumer()` | `RelayService.sendOrDrop(...)` | The policy is a relay decision, not a transport one, and it keeps `ClientConnection` at five methods. The spec's method would have been dead code. |
| 4 | `ErrorCode` gains `SLOW_CONSUMER` | Also gains `NOT_IMPLEMENTED` | So `SEND`/`ACK` answer honestly rather than going silent while their behaviour is still unbuilt. **Delete it in STORY-3.** |
| 5 | No logging component named | `Log.java` added | The server terminal is the demo's observability window; a framework's default format would need configuring, and a dependency was not worth it. |
| 6 | `TestClient.expectFrame(timeout)` | `expect(Class<T>)` with `SO_TIMEOUT` | Synchronous reads bounded by the socket mean no background thread to coordinate with — deterministic without a single `Thread.sleep`. |

**Known rough edge:** `WRITER_DRAIN_GRACE` is a hardcoded 500 ms inside `Connection` rather
than a `RelayConfig` bound. Defensible as an internal detail rather than an operator knob,
but it is the one number in the server not documented in the README.

## 12. Definition of Done

- [ ] Compiles (`mvn clean compile`)
- [ ] All tests green (`mvn test`)
- [ ] Every acceptance criterion met
- [ ] Manual verification passes
- [ ] ADR-003 written: concurrency model and the queue-full policy
- [ ] ADR-004 written: identity/connection split and the takeover decision
- [ ] PR opened summarising what / files / deviations / verification

---

## 13. Walkthrough essentials

### What was introduced

| Thing | What it is | Why it exists |
|---|---|---|
| **`ClientSession`** | Identity + lock + *nullable* connection | **The most important type in the project.** Identity outlives the socket, so reconnect and redelivery become possible |
| **`ClientRegistry`** | `ConcurrentHashMap` of sessions | Reattachment by name — returns the *same* session instance after a disconnect |
| **`ClientConnection`** | Five-method interface | Keeps `session` free of `java.net`, so STORY-3 can unit-test the mailbox with no sockets |
| **`RelayService`** | Exhaustive frame dispatch | The relay's behaviour, with no knowledge of sockets or threads |
| **`Connection`** | Socket + reader thread + writer thread + bounded queue | Where "a slow client doesn't block others" is actually won |
| **`RelayServer`** | Bind, accept loop, connection limit, shutdown | Lifecycle |
| **`Main` / `Log`** | Subcommand dispatch, config printout, shutdown hook | The demo's observability window |

### The architecture in one picture

```
   net  ──────▶  session  ──────▶  protocol
  (sockets,     (identity,        (frames,
   threads)      state)            codec)

  Per socket, dies with it        Per identity, survives it
  ┌─────────────────────────┐     ┌──────────────────────────┐
  │ reader ─▶ RelayService  │────▶│ ClientRegistry           │
  │ [bounded queue]         │◀────│   └─ ClientSession       │
  │ writer ─▶ socket        │     │        └─ (mailbox: S-3) │
  └─────────────────────────┘     └──────────────────────────┘
```

### The five sentences that carry the most weight

1. **"Identity outlives the connection."** `ClientSession` owns the state; the socket is a nullable field on it. Every requirement about disconnect and reconnect falls out of that placement.
2. **"Delivery never touches a socket."** It hands a frame to the recipient's bounded queue and returns. Otherwise a recipient that stopped reading would block whoever is delivering to it — which in STORY-3 is the *sender's* thread.
3. **"A full queue drops the connection, not the frame and not the caller."** Blocking reintroduces the problem; dropping the frame breaks the `ACCEPTED` promise. The session survives, so reconnecting recovers everything.
4. **"Closing is graceful because every close follows a frame the peer needs."** Poison pill, then wait on the writer's own exit — because `take()` removes a frame before writing it, so queue emptiness is the wrong signal.
5. **"Interrupt frees the writer from `take()`; closing the socket frees the reader and releases the descriptor."** And the nuance: the textbook claim that interrupt cannot break socket I/O holds for *platform* threads — virtual threads are NIO-backed and do unblock. Measured, not assumed.

### Three things to physically point at

- **`ClientSession.detach`** — the `if (this.connection == leaving)` identity check. One line, and without it a takeover kills the connection that just replaced the old one.
- **`Connection.close` vs `closeNow`** — graceful versus hard, and why the writer's own `finally` must call the hard one.
- **`RelayService.onFrame`** — an exhaustive switch with no `default`. Adding a frame type is a compile error here.

## 14. Questions to be able to answer

| # | Question | Answer anchor |
|---|---|---|
| 1 | What's the most important design decision in this codebase? | Identity is separate from connection. `ClientSession` owns state; `Connection` is a nullable field on it. |
| 2 | How do you stop a slow client blocking everyone else? | Per-connection writer thread and bounded queue. Delivery calls `offer` and returns; backpressure stays inside that one connection. |
| 3 | What happens when the outbound queue fills? | Drop that connection. Blocking reintroduces the problem; dropping the frame breaks the `ACCEPTED` promise. Session survives. |
| 4 | Why two threads per connection rather than one? | One thread would have to both block on the socket and drain the queue. The split is what keeps delivery off the socket. |
| 5 | Walk me through closing a connection. | Set `closing` → offer poison pill → await the writer's latch (bounded) → `closeNow`: interrupt writer, close socket, deregister. |
| 6 | Why not just wait for the queue to be empty? | `take()` removes the frame *before* writing it. The queue reads empty while the write is in flight. Wrong signal. |
| 7 | How do you unblock the reader, and would an interrupt work? | Closing the socket frees it and releases the descriptor. An interrupt *would* also free it here, because virtual threads are NIO-backed — the usual "interrupt can't break socket I/O" rule is a platform-thread rule. I measured both. |
| 8 | Why virtual threads? | Thread-per-connection with straight-line blocking I/O, at a cost we can afford. On Java 17 I'd have written an NIO selector loop with hand-rolled partial-read reassembly and OP_WRITE interest management. |
| 9 | Why `ReentrantLock` rather than `synchronized`? | On Java 21 a virtual thread blocking inside `synchronized` pins its carrier. JEP 491 fixed that in 24, but this targets 21 and may run on it. |
| 10 | Two connections claim the same name — what happens? | Newcomer wins, old one is told and closed. A half-open TCP connection is undetectable until a write fails, so rejecting would strand a client whose network dropped. |
| 11 | Show me the race that creates. | A registers, B takes over, A's reader finally exits and detaches — and must not null out B. `detach` checks identity. |
| 12 | Walk me through shutdown, in order. | Flag → close ServerSocket (that's what makes `accept()` throw) → offer SHUTDOWN to all → close all gracefully → `shutdownNow` + `awaitTermination`. |
| 13 | Why is `accept()` throwing not an error? | There's no "stop accepting" call. Closing the socket *is* the stop signal, and the throw is its designed exit route. |
| 14 | Why does `session` never import `java.net`? | So the mailbox in STORY-3 is unit-testable with a fake — no sockets, no threads, no timing. `ClientRegistryTest` already proves it. |
| 15 | How is the connection limit enforced without a race? | `incrementAndGet` then check, decrement if over. A `size()` read then add would let two accepts both slip past. |
| 16 | Why accept a connection just to reject it? | You can't refuse at the TCP level *and* say why. Accept, write `CONNECTION_LIMIT_REACHED`, close. |
| 17 | Where does the slow-consumer policy live, and why there? | `RelayService.sendOrDrop`. It's a relay decision, not a transport one, and it keeps the interface at five methods. |
| 18 | Are sessions ever cleaned up? | No. Registering many unique ids grows memory without bound. Accepted for the timebox; idle expiry is the named next step. |
| 19 | How would you detect a half-open connection sooner? | A keepalive. Not built — currently only detected on the next write. |
| 20 | Why is the switch exhaustive with no `default`? | `Frame` is sealed, so the compiler knows every case. Adding a type breaks compilation everywhere it isn't handled. |
| 21 | What does `NOT_IMPLEMENTED` do in your error codes? | Temporary scaffolding so `SEND`/`ACK` answer honestly while unbuilt. It is deleted in STORY-3. |
| 22 | What isn't covered by your tests? | Multi-JVM, real network partitions, load, and the JVM shutdown hook itself — the tests call `close()` directly. |
| 23 | Why one `Log` class rather than a logging framework? | The server terminal is the demo. A framework's default format needs configuring to be readable, and it wasn't worth a dependency. In production this would be a real logger — a documented limitation. |
| 24 | Is `WRITER_DRAIN_GRACE` configurable? | No — hardcoded 500 ms. It's the one number in the server not in `RelayConfig` or the README. Defensible as an internal detail; arguably should move. |
