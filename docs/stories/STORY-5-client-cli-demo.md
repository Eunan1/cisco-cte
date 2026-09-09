# STORY-5 — Client, CLI, and demo

| | |
|---|---|
| **Epic** | EPIC-2 — Guarantees and tooling |
| **Priority** | P0 (not a bonus — without this there is no demo) |
| **Points** | 3 |
| **Status** | Done — 88 tests green, CLI verified against a live server |
| **Depends on** | STORY-3 (something worth sending), STORY-1 (the codec the client reuses) |

> **This spec is written to be implemented by hand.** Unlike STORY-2 to 4, the code
> skeletons below are deliberately *partial* — signatures, structure, and the parts that are
> genuinely tricky, with the mechanical parts left for you. Where something is left out on
> purpose it says so.

## 1. Goal

A `RelayClient` — a small library that connects, registers, sends, acknowledges, and
receives pushed messages — and a `RelayCli` REPL on top of it, wired into `Main` as the
`client` subcommand.

After this story the three-terminal demo works end to end, and all seven of the brief's
requirements become something you can *show* rather than describe.

## 2. Why this matters

**It is the demo.** The interview opens with "explain it to us", and ninety seconds of
visible offline queueing and reconnect redelivery beats ten minutes of narrating class
diagrams. Beats 8 and 9 of §10 are what separate this submission from a competent one.

**It is also the best thing in the project to build by hand.** It is the least
algorithmically tricky piece, but writing it walks you through the entire data path —
`Frame` → `FrameCodec` → socket → server → mailbox → back — from the opposite end. You will
end up understanding the server better by writing its counterpart than by re-reading it.

And it contains one genuinely interesting design problem (§3.2) that is worth having met
before someone asks you about it.

## 3. Concepts & Theory

### 3.1 A client has two blocking inputs

The server's core insight was that a connection needs a reader thread and a writer thread,
because socket reads and queue takes both block. The client has the mirror-image problem:

```
   System.in.readLine()        blocks until the user types
   codec.readFrame(socket)     blocks until the server sends
```

Both must be live at once. A single-threaded client would either miss a `DELIVER` while
waiting for you to type, or ignore your typing while waiting for the server.

So: **the main thread reads stdin, a second thread reads the socket.** One thread per
blocking source, which is the same reasoning as the server's reader/writer split, seen from
the other side. That symmetry is worth pointing out during the tour.

No writer thread is needed — the CLI writes on the main thread, and a human types slowly
enough that blocking on a socket write is not a concern. Say that explicitly rather than
letting it look like an oversight.

### 3.2 Unsolicited pushes break naive request/response

This is the interesting problem.

```
   you type:  send bob hello
   you expect: ACCEPTED m1

   but what actually arrives on the socket might be:
       DELIVER  (a message from carol, pushed at that instant)
       ACCEPTED m1
```

A client that "sends, then reads the next frame and calls it the response" is wrong. It
would treat carol's message as the answer to your send, and lose it.

Three ways out:

| Approach | Verdict |
|---|---|
| **Async only** — the reader thread prints everything, `send()` returns void | Fine for a CLI, useless for a test that wants to assert on the response |
| **Route by kind** ✅ | The reader thread sends `DELIVER`/`SHUTDOWN` to a listener, and puts everything else on a response queue that `send()` can poll with a timeout |
| **`correlationId` on every frame** | Full request/response matching. Correct, and more than we need. |

Take the middle one. Frames split cleanly into **pushes** (`DELIVER`, `SHUTDOWN`) and
**replies** (`REGISTERED`, `ACCEPTED`, `REJECTED`, `ACK_OK`, `ERROR`), so routing on type is
enough.

**Its limitation, which you should volunteer:** it only works because a CLI user has one
operation in flight at a time. Two concurrent sends would each take whichever reply arrived
first, possibly the wrong one. That is exactly the situation a `correlationId` solves — the
field deliberately deferred in [ADR-002](../adr/ADR-002-framing-and-serialisation.md). Being
able to say *"here is the precise condition under which my simplification breaks, and here
is the field I would add"* is a strong answer.

### 3.3 Manual acknowledgement is a requirement, not a shortcut

The client must **not** auto-acknowledge on receipt.

Two reasons, and the second is the important one:

1. **The demo needs it.** Beats 8 and 9 depend on receiving a message and deliberately *not* acking before disconnecting. Auto-ack makes requirement 7 undemonstrable.
2. **Auto-ack would be wrong anyway.** Acknowledging on receipt means the server discards the message the moment it hits the socket — so a client that crashes while processing loses it. That is at-most-once, not at-least-once. The ack means *"I have taken responsibility"*, which only the application can say.

In a real client library, `ack()` is called by application code after its handler returns
successfully. In the CLI a human plays that role.

### 3.4 You cannot interrupt a thread blocked on `System.in`

When the server goes away — `SHUTDOWN`, or the socket dies — the reader thread notices, but
the main thread is parked in `readLine()` and there is no way to wake it. `interrupt()` does
not work on console reads.

Options: have the reader thread call `System.exit(0)`, or print a notice and let the user
press Enter.

**`System.exit(0)` is the right answer for a CLI** and worth stating as a deliberate choice
rather than leaving it to look accidental. In a library you would never do this; in a
terminal program whose only job is this session, exiting when the connection dies is
correct behaviour.

### 3.5 Message ids can collide between clients

Ids are unique **per recipient mailbox**, not globally. So if alice and carol both number
their messages `m1` and both send to bob, the second is rejected with
`DUPLICATE_MESSAGE_ID` — during your demo, in front of people.

Prefix the id with the sender's own name: `alice-1`, `carol-1`. One line, and it makes the
demo output easier to read as well.

## 4. Design & Approach

### Package

New package `com.eunangavin.relay.client`, holding both classes.

Note the earlier decision to *rename* this from `cli`: the package contains a transport
library, not just a command-line interface, and `client` predicts its imports honestly.
`net` remains server-side.

### The split

| Class | Role | Knows about |
|---|---|---|
| `RelayClient` | Socket, codec, reader thread, response queue, `register`/`send`/`ack` | The protocol. **Not** `System.in` or `System.out`. |
| `RelayCli` | Parses typed commands, prints frames, owns the REPL loop | `RelayClient`, and the console |

Keeping the console out of `RelayClient` is what lets it be tested. If `RelayClient` prints
anything, it is wrong.

### On `TestClient`

`RelayClient` overlaps with the existing test-scope `TestClient`. **Do not migrate the four
existing test classes onto it** — that is churn with no marks attached, and it risks
destabilising 82 passing tests two days before the interview.

Write one new `RelayClientTest` so the library itself is covered, and record the duplication
as a deliberate scope call. If asked: *"`TestClient` is a test fixture with synchronous
socket-timeout reads; `RelayClient` is a real client with a reader thread and push
handling. Converging them was not worth the risk this close to submission."*

## 5. Files to create / modify

| Path | Purpose |
|---|---|
| `client/RelayClient.java` | The library: connect, register, send, ack, receive pushes |
| `client/RelayCli.java` | The REPL: parse commands, print frames |
| `Main.java` | **Modify:** implement the `client <name>` subcommand |
| `config/RelayConfig.java` | **Modify:** add `RELAY_HOST` — built as a static `clientHost()` lookup, *not* a record component (see §11a) |
| `test/.../RelayClientTest.java` | End-to-end coverage of the library |
| `docs/DEMO.md` | The nine beats, as a script to follow live |
| `test/.../RelayConfigTest.java` | **Modify:** one test for `RELAY_HOST` |
| ~~`client/ReplayCli.java`~~ | **Deleted** — an empty file created from a typo (`Replay` for `Relay`) |

## 6. Implementation

Partial on purpose. The signatures and the hard parts are here; the bodies mostly are not.

### 6.1 `RelayClient`

```java
public final class RelayClient implements AutoCloseable {

    /** Frames the server pushes without being asked. */
    public interface MessageListener {
        void onDeliver(Frame.Deliver deliver);
        void onShutdown(Frame.Shutdown shutdown);
        void onDisconnected(String reason);
    }

    private final Socket socket;
    private final DataInputStream in;
    private final DataOutputStream out;
    private final FrameCodec codec;

    /** Replies to things WE asked for. Pushes never land here - see section 3.2. */
    private final BlockingQueue<Frame> replies = new LinkedBlockingQueue<>();

    private final AtomicInteger nextMessageId = new AtomicInteger(1);
    private volatile boolean closed;

    public static RelayClient connect(String host, int port, MessageListener listener)
            throws IOException {
        // Open the socket, wrap the streams (buffered, then Data* - as Connection does),
        // start the reader thread, return.
    }

    // --- operations. Each sends a frame and waits for its reply. -------------------

    public Frame.Registered register(String clientId) throws IOException { }

    /** @return ACCEPTED or REJECTED. Generates the id; see section 3.5 about prefixing. */
    public Frame send(String to, String payload) throws IOException { }

    public Frame.AckOk ack(String messageId) throws IOException { }

    // --- the reader thread ---------------------------------------------------------

    private void readLoop(MessageListener listener) {
        // while a frame arrives:
        //     DELIVER  -> listener.onDeliver(...)
        //     SHUTDOWN -> listener.onShutdown(...)
        //     anything else -> replies.put(frame)
        //
        // A null from readFrame means a clean close. Either way, on exit call
        // listener.onDisconnected(reason) exactly once.
    }

    /** Blocks for a reply, with a deadline so a silent server fails rather than hangs. */
    private Frame awaitReply() throws IOException {
        Frame reply = replies.poll(RESPONSE_TIMEOUT.toMillis(), MILLISECONDS);
        if (reply == null) throw new IOException("no response within " + RESPONSE_TIMEOUT);
        return reply;
    }
}
```

**Points to get right**

- The reader thread should be a **daemon** (or a virtual thread) so it cannot keep the JVM alive after `quit`.
- `awaitReply` must have a timeout. An unbounded `take()` hangs your demo with no explanation.
- `onDisconnected` must fire exactly once, from the reader thread's `finally`.
- `close()` should be idempotent — the user typing `quit` and the server closing can race.

### 6.2 `RelayCli`

```java
public final class RelayCli implements RelayClient.MessageListener {

    // commands: send <to> <text...>   ack <messageId>   help   quit

    public static void run(String clientId, String host, int port) throws IOException { }

    @Override public void onDeliver(Frame.Deliver d) {
        // Print it AND the ack command, so the demo needs no memory:
        //   << DELIVER m1 from alice : hello      <-- type 'ack m1'
    }

    @Override public void onDisconnected(String reason) {
        // Print, then System.exit(0) - see section 3.4.
    }
}
```

Print incoming frames with a `<<` prefix and outgoing confirmations plainly, so the two are
distinguishable at a glance on a shared screen. Do not add a prompt — a background thread
printing between your prompt and your typing looks broken.

### 6.3 `Main`

Replace the `client` placeholder. Read host and port from `RelayConfig`, so the same
binary works locally and in Docker.

### 6.4 `RelayConfig`

Add `host`, defaulting to `localhost`, overridable by `RELAY_HOST`. Remember to update
`RelayConfigTest.overridesEveryDocumentedVariable` and the boot printout in `Main`.

> This is the smallest change in the story and the easiest to forget. STORY-6's Docker demo
> reaches the server by container name and will not work without it.

## 7. Gotchas & pitfalls

1. **Do not auto-acknowledge.** It makes requirement 7 undemonstrable and it is wrong on its own terms (§3.3).
2. **Do not read the socket on the main thread.** You will miss pushes while the user types.
3. **Prefix message ids with the client name** (§3.5) or your demo will hit `DUPLICATE_MESSAGE_ID`.
4. **`awaitReply` needs a deadline.** An unbounded wait turns a server bug into a hung terminal.
5. **Daemon or virtual reader thread**, or `quit` will not actually exit.
6. **`RelayClient` must not print.** The moment it does it stops being testable.
7. **Do not migrate the existing tests onto `RelayClient`.** 82 tests pass; leave them alone.
8. **Buffer the streams** before the `Data*` wrappers, as `Connection` does.
9. **`System.exit(0)` from the reader thread is deliberate** (§3.4) — write a comment saying so, or it reads as a mistake.
10. **Remember `RELAY_HOST` in three places:** the record, `fromEnvironment`, and the boot printout.

## 8. Acceptance Criteria

- [x] `RelayClient.connect` establishes a connection and starts a reader thread.
- [x] `register` returns `REGISTERED` with the pending count.
- [x] `send` returns `ACCEPTED` or `REJECTED`, correctly, **even when a `DELIVER` arrives in between**.
- [x] `ack` returns `ACK_OK`.
- [x] Pushed `DELIVER` frames reach the listener and never the reply queue.
- [x] `SHUTDOWN` reaches the listener.
- [x] A dropped connection calls `onDisconnected` exactly once.
- [x] `RelayClient` contains no `System.out` or `System.in`.
- [x] The CLI supports `send`, `ack`, `help`, `quit`, and prints the ack command alongside each delivery.
- [x] The CLI does **not** auto-acknowledge.
- [x] `java -cp ... Main client alice` connects and registers.
- [x] `RELAY_HOST` is honoured and appears in the boot printout.
- [ ] All nine demo beats in §10 run start to finish, twice. *(not yet rehearsed)*
- [x] Full suite green, run 5×.

## 9. Tests to write

Four or five. `RelayClientTest` runs a real `RelayServer` on port 0, exactly as
`DeliveryTest` does.

| Test | Asserts |
|---|---|
| `registerSendAndAckRoundTrip` | The happy path through the library |
| `deliveredMessagesReachTheListenerNotTheReplyQueue` | **The §3.2 test** — have the listener capture, then assert a following `send` still gets its own `ACCEPTED` |
| `rejectionIsReturnedNotThrown` | A `REJECTED` is a normal return value, not an exception |
| `disconnectNotifiesTheListenerOnce` | Stop the server; assert `onDisconnected` fires exactly once |
| `sendGeneratesUniquePrefixedIds` | Two clients sending to the same recipient do not collide |

The second is the one worth building carefully: arrange for a `DELIVER` to be in flight
while a `send` is awaiting its reply. Two clients and a well-placed ordering does it.

## 10. Manual verification — the nine beats

**This is both the acceptance test for this story and the demo you will give.** Run it
twice, start to finish, and do not skip beats 8 and 9.

```
Terminal 1   java -cp <cp> com.eunangavin.relay.Main server
Terminal 2   java -cp <cp> com.eunangavin.relay.Main client alice
Terminal 3   java -cp <cp> com.eunangavin.relay.Main client bob
```

| # | Where | Do this | Expect | Proves |
|---|---|---|---|---|
| 1 | T1 | start the server | six bounds printed, `listening on 9090` | limits are configured, not hard-coded |
| 2 | T2, T3 | start alice and bob | `REGISTERED ... pending=0` in both | **req 1** |
| 3 | T2 | `send bob hello` | T2: `ACCEPTED alice-1` · T3: `DELIVER alice-1 from alice` | **req 2, 3, 4** |
| 4 | T3 | `ack alice-1` | `ACK_OK`; T1 logs the mailbox back to 0 | acknowledgement removes it |
| 5 | T3 | Ctrl-C | T1: `session 'bob' retained ... now offline` | identity outlives the connection |
| 6 | T2 | `send bob one` then `send bob two` | both `ACCEPTED`; T1 shows bob holding 2 | **req 5** |
| 7 | T3 | restart as bob | `REGISTERED pending=2`, then both `DELIVER`s in order | **req 6** |
| 8 | T3 | Ctrl-C **without acking** | T1 logs the session retained with 2 | — |
| 9 | T3 | restart as bob | **both redelivered, in the same order** | **req 7** — the money shot |

Then two extras worth thirty seconds each:

- `send nobody hi` → `REJECTED ... UNKNOWN_RECIPIENT`, **and the connection survives** — try another send immediately to show it.
- Ctrl-C the server with clients attached → each client prints `SHUTDOWN`, then exits cleanly.

Write the beats into `docs/DEMO.md` so you are reading, not improvising.

## 11. Out of scope

| Not in this story | Owned by |
|---|---|
| Shaded jar, so `java -jar` works | STORY-6 |
| Dockerfile and the container demo | STORY-6 |
| `README.md`, `APPROACH.md` | STORY-6 |
| Auto-reconnect in the client | Deliberately not built — reconnect must be visible in the demo |
| `correlationId` for concurrent in-flight requests | Documented limitation (§3.2); next step |
| Migrating existing tests onto `RelayClient` | Deliberate scope call (§4) |

## 11a. Deviations from the original spec

| # | Spec said | Built instead | Why |
|---|---|---|---|
| 1 | Add `RELAY_HOST` to `RelayConfig` | A static `RelayConfig.clientHost()` lookup | The record holds the **server's** bounds, and the server never needs a host — it binds `0.0.0.0`. Making it a component would force every server test to construct a value it never uses, and churn ~10 call sites two days before submission. |
| 2 | `register` / `send` / `ack` throw only `IOException` | They also throw `ProtocolException` | An `ERROR` reply has to surface somehow. Reusing the existing protocol exception beats inventing a client-specific one, and it carries the `ErrorCode`. |
| 3 | *(nothing)* | A `ReentrantLock` guarding the output stream | Cheap insurance if a caller ever uses one instance from two threads. The class documents itself as single-threaded, but the lock means misuse corrupts nothing. |
| 4 | `volatile boolean closed` | Two `AtomicBoolean`s — `closed` and `disconnectReported` | "Fires exactly once" needs its own flag: the reader's `finally` is reachable by several routes, and the CLI calls `System.exit` from that callback. |
| 5 | *(nothing)* | `System.out.flush()` before `System.exit(0)` | **Found by running it.** `System.exit` does not flush, and stdout is block-buffered whenever redirected — the tail of the disconnect line was being lost. Invisible to any unit test. |
| 6 | *(nothing)* | A BOM guard in the command parser | **Found by running it.** A UTF-8 BOM survives `trim()`, so the first piped command hit the unknown-command branch. Irrelevant when typed, breaks any scripted demo. |
| 7 | "cap at ~60 lines" | 99 code lines | Over the stated cap. The excess is defensive: `tryRegister`, the `require` helper, the catch-all so a typo cannot kill the session mid-demo, and the BOM guard. Still four commands, no history, no prompt — the *shape* held even though the line count did not. |
| 8 | *(nothing)* | `DEMO.md` rewritten with per-shell classpath blocks | The first draft used a `<cp>` placeholder, which PowerShell rejects outright (`<` is a reserved operator). Replaced with copy-pasteable blocks using forward slashes — Java accepts them on Windows, so nothing needs escaping. |

**Note on 5 and 6:** both defects were unreachable from the test suite. They needed a real
process with real redirected output. Worth remembering that a green suite is not the same
as a working program.

## 12. Definition of Done

- [ ] Compiles (`mvn clean compile`)
- [ ] All tests green (`mvn test`), run 5×
- [ ] Every acceptance criterion met
- [ ] Nine beats run twice, start to finish
- [ ] `docs/DEMO.md` written
- [ ] Sections 13 and 14 added
- [ ] Spec reconciled against the implementation, with deviations recorded in §11a

---

## 13. Walkthrough essentials

### What was introduced

| Thing | What it is | Why it exists |
|---|---|---|
| **`RelayClient`** | Socket, codec, reader thread, push/reply routing | A client/server exercise needs a client. Used by tests and by the CLI; contains no console I/O at all |
| **`RelayClient.MessageListener`** | `onDeliver` / `onShutdown` / `onDisconnected` | How unsolicited frames get out without the library knowing what a terminal is |
| **`RelayCli`** | Four commands, no prompt, no history | The demo. Deliberately minimal — the brief lists "a user interface" as not required |
| **`RelayConfig.clientHost()`** | `RELAY_HOST`, default `localhost` | A *client* concern. The server binds `0.0.0.0` and never needs it |
| **`docs/DEMO.md`** | The nine beats, with the sentence to say at each | So the demo is read, not improvised |

### The shape

```
   ┌──────────────── RelayCli (the console) ────────────────┐
   │  main thread:  readLine() ──▶ send / ack / help / quit │
   │  listener:     onDeliver, onShutdown, onDisconnected   │
   └───────────────────────┬────────────────────────────────┘
                           │  no System.out below this line
   ┌───────────────────────▼────────────────────────────────┐
   │  RelayClient                                            │
   │                                                         │
   │   reader thread ──▶ DELIVER / SHUTDOWN ──▶ listener     │
   │        (socket)  └─▶ everything else   ──▶ replies queue│
   │                                              ▲          │
   │   register / send / ack ──▶ writeFrame ──────┘ awaitReply
   └─────────────────────────────────────────────────────────┘
```

### The five sentences that carry the most weight

1. **"A client has two blocking inputs, so it needs two threads."** The user and the socket both block; one thread can serve one blocking source. Same reasoning as the server's reader/writer split, seen from the other end.
2. **"The reader routes by kind, not by arrival."** A `DELIVER` can land between your `SEND` and its `ACCEPTED` — a client that reads "the next frame" as the response would take someone else's message as its answer and lose it.
3. **"That works because a CLI has one operation in flight at a time."** Two concurrent sends would each take whichever reply came first. That is exactly what a `correlationId` fixes — the field I deferred in ADR-002.
4. **"It does not auto-acknowledge, and that isn't laziness."** Acking on receipt would let the server discard a message the instant it hit the socket, so a client dying mid-processing loses it — at-most-once, not at-least-once.
5. **"No writer thread, and that asymmetry is deliberate."** The server writes to one client on another client's thread, so it must decouple. The client only writes when a human types.

### Three things to physically point at

- **`RelayClient.readLoop`** — the switch that routes pushes to the listener and everything else to the queue. Four lines, and the whole design is in them.
- **`RelayClient.awaitReply`** — the deadline. An unbounded `take()` would turn a server bug into a terminal that hangs with no explanation, mid-demo.
- **`RelayCli.onDisconnected`** — `flush()` then `System.exit(0)`, both commented as deliberate. The exit is because you cannot interrupt a thread parked in `readLine()`.

### The story worth telling

> *"The suite was green and the client still had two bugs. `System.exit` doesn't flush
> `System.out`, and stdout is block-buffered whenever it's redirected — so the tail of the
> last line vanished whenever output was piped or captured. And a UTF-8 BOM survives
> `trim()`, so the first command of any scripted run silently hit the unknown-command
> branch. Neither is reachable from a unit test. I only found them by running the thing."*

A green suite is not the same as a working program. That is a good thing to have learned
out loud.

## 14. Questions to be able to answer

| # | Question | Answer anchor |
|---|---|---|
| 1 | Why does the client need two threads? | Two independent blocking sources — the user and the socket. A blocked thread is suspended by the OS and cannot do anything else, so one thread can serve one source. |
| 2 | What actually happens if you use one thread? | The `DELIVER` is not lost — the OS buffers it — but it is only read when you next happen to read the socket, i.e. after you finish typing. Arbitrarily late, which looks like a broken server. |
| 3 | How do you tell a reply from a pushed message? | By kind. `DELIVER` and `SHUTDOWN` go to the listener; everything else goes to a reply queue the calling thread drains. |
| 4 | Why not just read the next frame after sending? | Because a `DELIVER` can arrive between your `SEND` and its `ACCEPTED`. You would return someone else's message as your response and lose it. |
| 5 | When does your approach break? | Two operations in flight at once — each would take whichever reply arrived first. A CLI never does that. `correlationId` is the fix, and it is already named as deferred in ADR-002. |
| 6 | Why doesn't the client auto-acknowledge? | It would be at-most-once: the server discards on delivery, so a client that dies mid-processing loses the message. The ack means "I have taken responsibility", which only the application can say. It also makes requirement 7 undemonstrable. |
| 7 | Why no writer thread on the client? | The server writes to one client on *another* client's thread, so it must decouple or a slow reader stalls the sender. The client only writes when a human types, one at a time. |
| 8 | Why `System.exit(0)` from a callback? | The main thread is parked in `readLine()` and console reads cannot be interrupted. For a terminal program whose only job is this session it is correct; in a library it would not be. |
| 9 | Why is `RELAY_HOST` not in `RelayConfig`? | It is a client concern. The server binds `0.0.0.0` and never needs a host, so making it a record component would force every server test to construct an unused value. |
| 10 | Why prefix message ids with the client name? | Ids are unique per *recipient mailbox*, not globally. Two clients both numbering from 1 and sending to the same person would collide with `DUPLICATE_MESSAGE_ID`. |
| 11 | Why does `send` return a `REJECTED` instead of throwing? | An unknown recipient or a full mailbox is a normal outcome the caller must handle, not an exceptional one. An `ERROR` frame *does* throw, because that means the request was not understood. |
| 12 | Why does `awaitReply` have a timeout? | An unbounded wait turns a server-side bug into a hung terminal with no explanation — the worst possible failure mid-demo. |
| 13 | Why does `RelayClient` contain no printing? | The moment it prints, it stops being testable. All console I/O lives in `RelayCli`. |
| 14 | You still have `TestClient` — why two clients? | `TestClient` is a synchronous fixture with socket timeouts; `RelayClient` is a real client with a reader thread and push handling. Converging them was churn with no marks attached, this close to submission. |
| 15 | The brief says no user interface — why is there a CLI? | A length-prefixed binary protocol cannot be driven by hand; `netcat` is useless against it. The exclusion is about GUIs and frontends. This is four commands and no prompt, and being able to *show* offline retention beats describing it. |
| 16 | Did your tests catch everything? | No — two real defects were invisible to them. See §11a, items 5 and 6. |
