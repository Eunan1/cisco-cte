# STORY-3 — Mailbox, delivery, and acknowledgement

| | |
|---|---|
| **Epic** | EPIC-1 — Core relay |
| **Priority** | P0 |
| **Points** | 8 |
| **Status** | Done — validated, 72 tests green |
| **Depends on** | STORY-2 (sessions to hang mailboxes off, connections to deliver through) |

## 1. Goal

The core exercise, finished.

A bounded `Mailbox` hanging off each `ClientSession`, holding **pending** messages and
**inflight** ones separately. `SEND` validates, bound-checks, enqueues under the
recipient's lock and answers `ACCEPTED` or `REJECTED`. A delivery pump moves messages to an
attached connection. `ACK` removes a message — and only the rightful recipient's ack can.
Disconnect requeues inflight to the head of pending, so reconnecting redelivers.

After this story **all seven of the brief's core requirements are met**. Everything
afterwards is bonus, tooling, or presentation.

## 2. Why this matters

Requirement 7 — *"a message delivered before a disconnect but not acknowledged remains
available for redelivery after reconnecting"* — is the line that separates submissions. It
forces the admission that **delivered and acknowledged are different states**, which forces
the two-structure mailbox, which forces at-least-once semantics, which forces the duplicate
conversation. Most of the interesting design in this project descends from that one
sentence, and it all lands here.

This is also where the concurrency property built in STORY-2 gets *proved*. Until now
nothing has ever delivered to another client, so "a slow client does not block unrelated
clients" has been an assertion about the design. The test at the end of this story is the
first thing that demonstrates it.

## 3. Concepts & Theory

### 3.1 Two structures, not one

A mailbox needs to answer two different questions: *what has not been sent yet?* and *what
has been sent but not confirmed?*

```
  Mailbox for "bob"
  ┌──────────────────────────────┬──────────────────────────────────┐
  │ pending: ArrayDeque<Message> │ inflight: LinkedHashMap<Id,Msg>  │
  │  [m4, m5, m6]                │  {m2: …, m3: …}                  │
  │  never sent                  │  sent, awaiting ACK              │
  └──────────────────────────────┴──────────────────────────────────┘
        ▲                    │                        │
        │  requeue on        │ pump                   │ ack
        │  disconnect        ▼                        ▼
        └──────────────── inflight ──────────────▶ removed
```

**Why not one list with a cursor?** A cursor ("everything before index *n* has been sent")
breaks the moment an ack removes an element from the middle — which is normal, because acks
may arrive out of order. Every removal would shift the cursor, and reconstructing which
side of it each message belonged to is exactly the bookkeeping the two structures give for
free.

**Why `LinkedHashMap` for inflight?** Two properties at once: O(1) lookup by message id for
the ack path, and *insertion order* preserved for the requeue path. A plain `HashMap` loses
the ordering that FIFO redelivery depends on.

**Why `ArrayDeque` for pending?** Append at the tail for new messages, prepend at the head
for requeued ones. Both O(1). Choosing a deque on day one is what makes STORY-4 nearly free
rather than a restructure.

### 3.2 What `ACCEPTED` actually promises

`ACCEPTED` means **"this message is in the recipient's mailbox"**. It does *not* mean
delivered, and it certainly does not mean read.

That distinction is why the brief lists requirement 3 (*the service confirms whether it
accepted or rejected a send*) and requirement 4 (*the recipient receives and explicitly
acknowledges*) as separate lines. Two different parties confirm two different things:

| Frame | Who sends it | What it asserts |
|---|---|---|
| `ACCEPTED` | Server → sender | I have taken custody of this message |
| `ACK` | Recipient → server | I have taken responsibility for this message |

Collapsing them would mean either lying to the sender or making the sender wait for the
recipient — and the recipient may be offline for hours.

### 3.3 Ack ownership: structural, not checked

The brief says *"a message is removed only after the **correct recipient** acknowledges
it"*. There are two ways to honour that.

**Option A — a global message index, plus an ownership check.**

```java
Message m = allMessages.get(ackedId);
if (!m.recipient().equals(acker.clientId())) return error(INVALID_ACK);
```

**Option B — scope the lookup to the acker's own mailbox.** ✅

```java
ackingSession.mailbox().ack(ackedId);   // can only ever touch this client's messages
```

We take **B**, and it is the stronger answer: a wrong-recipient ack cannot remove anything
because it never has a reference to anyone else's mailbox. It is **structurally
impossible**, not a validation someone remembered to write. Option A passes the same tests
while leaving a `remove(id)` on a shared map one refactor away from a serious bug.

The cost, which should be stated: we can no longer *distinguish* a wrong-recipient ack from
a stale one — both are "that id is not in your mailbox" — so both return `ACK_OK`. That is
fine, and §3.4 explains why it is actually required.

> **Consequence:** `ErrorCode.INVALID_ACK` becomes unreachable and is deleted in this
> story, along with the temporary `NOT_IMPLEMENTED`. Update the semantics table in
> `OVERVIEW.md`, which currently promises `INVALID_ACK`.

### 3.4 Stale and repeated acks must be idempotent

At-least-once delivery **guarantees** a client will sometimes ack the same message twice:
it acks, the connection drops before the ack lands, it reconnects, receives the message
again, and acks again. That is the system working correctly.

So a repeated or unknown ack returns `ACK_OK` and changes nothing. Returning an error would
punish a client for behaviour our own delivery guarantee forces on it.

### 3.5 Delivery is pipelined

The pump drains everything it can rather than waiting for each ack.

```
   pending [m1 m2 m3]  ──pump──▶  offer(m1), offer(m2), offer(m3)  ──▶  outbound queue
   inflight {m1 m2 m3}
```

Stop-and-wait — deliver one, await its ack, deliver the next — would cost a round trip per
message and buy nothing, because the bounded outbound queue already provides flow control.

Two consequences: several messages are inflight at once, and **acks may arrive out of
order**. That is why inflight is a map keyed by id rather than a queue. The ordering promise
covers *delivery* order only.

### 3.6 Redelivery is triggered by reconnect, and nothing else

There is **no ack timeout**. A client that stays connected and never acks holds a message
in inflight indefinitely.

So the guarantee must be stated precisely: **at-least-once, with redelivery on reconnect**.
Not unqualified at-least-once. An ack timeout with a bounded retry count is the named next
step — say this before an interviewer finds the gap, because they will look for it.

### 3.7 Where the pump runs, and why that is the whole concurrency story

The pump runs on **the calling thread, under the recipient's session lock** — and for a
`SEND`, the calling thread is the *sender's* reader thread.

That sounds alarming and is fine, because of one property: `offer` never blocks. It puts a
frame on the recipient's bounded queue and returns. The recipient's own writer thread does
the socket write. Alice's thread therefore never touches Bob's socket, no matter how badly
Bob is behaving.

**Two rules follow, and breaking either undoes STORY-2's work:**

1. **Never write to a socket from the pump.** Only `offer`.
2. **Never close a connection while holding the session lock.** `close` is graceful and waits up to 500 ms for the writer to drain — holding a lock across that would let one slow socket stall every send to that identity. The pump therefore *returns* a connection that needs closing and lets the caller do it outside the lock, exactly as `ClientSession.attach` already returns an evicted connection.

## 4. Design & Approach

### The message

```java
public record Message(String id, String from, String payload) { }
```

Client-supplied id, so a client can retry a send idempotently, and the server echoes it as
the ack key. No timestamp: nothing uses one, and unused fields invite questions.

### Mailbox invariants

- `pending.size() + inflight.size() <= maxMailboxMessages`. Inflight counts, because it is retained state and the brief says mailboxes are bounded.
- A message id is unique across pending and inflight *for one recipient*, which is what makes duplicate detection possible.
- Not thread-safe by itself. **Always called under the owning `ClientSession`'s lock** — documented on the class, because an unguarded mailbox would be a subtle disaster.

### Mailbox-full policy: reject the newest

Evicting the oldest would silently discard a message we already told a sender was
`ACCEPTED`, breaking the one promise that frame makes. Rejecting the newest tells the
sender something they can act on. State this trade-off out loud — the alternative is
defensible for some systems, and knowing why it is wrong *here* is the point.

### Duplicate `messageId`

Rejected with `DUPLICATE_MESSAGE_ID` while the id is live in the target mailbox. Once acked
and evicted, the same id would be accepted again as a new message.

That gap is deliberate and must be documented: unbounded dedupe history is a memory leak,
and the brief demands bounded state. A bounded LRU of recently-acked ids is the next step.

### SEND validation order

Cheapest and most-specific first, so an expensive check never runs for a request that was
going to fail anyway:

```
  registered?          → NOT_REGISTERED
  payload within limit? → PAYLOAD_TOO_LARGE      (connection survives - the frame was fine)
  recipient exists?     → UNKNOWN_RECIPIENT
  id not already live?  → DUPLICATE_MESSAGE_ID
  mailbox has room?     → MAILBOX_FULL
  ────────────────────────────────────────
  enqueue, ACCEPTED, then pump
```

## 5. Files to create / modify

| Path | Purpose |
|---|---|
| `session/Message.java` | The record: id, from, payload |
| `session/Mailbox.java` | `pending` deque + `inflight` map, bounded; all the queueing logic |
| `session/ClientSession.java` | **Modify:** owns a `Mailbox`; `enqueue`, `pump`, `ack`, `requeueInflight` under its lock |
| `session/RelayService.java` | **Modify:** implement `SEND` and `ACK`; pump on register; report `pending` in `REGISTERED` |
| `session/ClientRegistry.java` | **Modify:** takes `maxMailboxMessages` and passes it to every session it creates |
| `protocol/ErrorCode.java` | **Modify:** delete `NOT_IMPLEMENTED` and `INVALID_ACK` (see §3.3) |
| `Main.java` | **Modify:** the two constructors above now need config |
| `test/.../RelayServerTest.java`, `ClientRegistryTest.java` | **Modify:** same constructor changes |
| `test/.../MailboxTest.java` | Pure unit tests — no sockets, no threads |
| `test/.../DeliveryTest.java` | End-to-end over sockets: delivery, ack, redelivery, isolation |
| `docs/OVERVIEW.md` | **Modify:** the semantics table still promises `INVALID_ACK` |

## 6. Implementation

### 6.1 `Mailbox`

```java
/** NOT thread-safe. Every method must be called under the owning ClientSession's lock. */
final class Mailbox {          // package-private: nothing outside `session` needs it

    /** A boolean cannot distinguish MAILBOX_FULL from DUPLICATE_MESSAGE_ID in the reply. */
    enum EnqueueResult { ACCEPTED, MAILBOX_FULL, DUPLICATE_ID }

    private final int maxMessages;
    private final ArrayDeque<Message> pending = new ArrayDeque<>();
    private final LinkedHashMap<String, Message> inflight = new LinkedHashMap<>();

    EnqueueResult offer(Message message) {
        // Duplicate is checked BEFORE capacity, deliberately: a resend of a message already
        // in the mailbox should be told it is a duplicate, not that the mailbox is full.
        // The reverse order would give a misleading answer whenever both were true.
        if (contains(message.id())) return EnqueueResult.DUPLICATE_ID;
        if (size() >= maxMessages)  return EnqueueResult.MAILBOX_FULL;
        pending.addLast(message);
        return EnqueueResult.ACCEPTED;
    }

    /** Moves the head to inflight and returns it, or null when nothing is pending. */
    Message takeForDelivery() {
        Message m = pending.pollFirst();
        if (m != null) inflight.put(m.id(), m);
        return m;
    }

    /** Puts a message back at the head — used when the outbound queue refuses it. */
    void returnToFront(Message m) {
        inflight.remove(m.id());
        pending.addFirst(m);
    }

    /** @return true if this mailbox actually held that id. Stale acks are simply false. */
    boolean ack(String messageId) {
        return inflight.remove(messageId) != null;
    }

    /**
     * On disconnect, everything delivered but unacknowledged goes back to the FRONT of
     * pending, in its original order — requirement 7, and the basis of FIFO in STORY-4.
     */
    void requeueInflight() {
        List<Message> toRequeue = new ArrayList<>(inflight.values());   // insertion order
        inflight.clear();
        // Iterate in REVERSE: addFirst on a forward iteration would reverse the batch.
        for (int i = toRequeue.size() - 1; i >= 0; i--) {
            pending.addFirst(toRequeue.get(i));
        }
    }

    int size()          { return pending.size() + inflight.size(); }
    int pendingCount()  { return pending.size(); }
    int inflightCount() { return inflight.size(); }
    private boolean contains(String id) {
        return inflight.containsKey(id) || pending.stream().anyMatch(m -> m.id().equals(id));
    }
}
```

> `contains` is a linear scan of pending. At `maxMailboxMessages = 1000` that is fine, and
> it avoids a third structure to keep in sync. Worth naming as a known trade-off: a
> `HashSet` of live ids would make it O(1) at the cost of another thing to keep consistent.

### 6.2 `ClientSession` — the pump

```java
/** @return a connection the CALLER must close (outbound queue full), or null. */
ClientConnection pump() {
    lock.lock();
    try {
        ClientConnection conn = this.connection;
        if (conn == null) return null;              // offline: messages simply wait

        Message m;
        while ((m = mailbox.takeForDelivery()) != null) {
            if (!conn.offer(new Frame.Deliver(m.id(), m.from(), m.payload()))) {
                mailbox.returnToFront(m);           // not delivered - keep it pending
                return conn;                        // caller closes, OUTSIDE this lock
            }
        }
        return null;
    } finally {
        lock.unlock();
    }
}
```

Holding the lock across `conn.offer` is safe *only* because `offer` is non-blocking. That
sentence is worth being able to say without hesitating.

**Both `attach` and `detach` requeue.** `detach` is the obvious one — a disconnect strands
whatever was inflight. But a **takeover** strands it just as thoroughly: the evicted
connection is never coming back to acknowledge anything, so `attach` requeues before
binding the newcomer. Missing this leaves messages inflight to a dead connection with
nobody left to ack them, and only a test that reconnects *without* first disconnecting
would catch it.

### 6.3 `RelayService` — SEND and ACK

```java
case Frame.Send s   -> handleSend(connection, s);
case Frame.Ack  a   -> handleAck(connection, a);
```

```java
private void handleSend(ClientConnection conn, Frame.Send send) {
    ClientSession sender = conn.session();
    if (sender == null) { sendOrDrop(conn, error(NOT_REGISTERED, "register first")); return; }

    if (utf8Length(send.payload()) > config.maxPayloadBytes()) {
        // The frame parsed fine, so the connection survives - only the message is refused.
        sendOrDrop(conn, rejected(send, PAYLOAD_TOO_LARGE, "...")); return;
    }

    Optional<ClientSession> recipient = registry.find(send.to());
    if (recipient.isEmpty()) { sendOrDrop(conn, rejected(send, UNKNOWN_RECIPIENT, "...")); return; }

    var message = new Message(send.messageId(), sender.clientId(), send.payload());
    // Only the RECIPIENT's lock is taken, never both - locking sender and recipient
    // together would deadlock the moment two clients sent to each other at once.
    Mailbox.EnqueueResult outcome = target.enqueue(message);

    switch (outcome) {                      // exhaustive over the enum
        case DUPLICATE_ID -> reject(conn, send, DUPLICATE_MESSAGE_ID, "...");
        case MAILBOX_FULL -> reject(conn, send, MAILBOX_FULL, "...");
        case ACCEPTED -> {
            sendOrDrop(conn, new Frame.Accepted(send.messageId()));
            pump(target);                   // no-op when the recipient is offline
        }
    }
}

private void handleAck(ClientConnection conn, Frame.Ack ack) {
    ClientSession session = conn.session();
    if (session == null) { sendOrDrop(conn, error(NOT_REGISTERED, "register first")); return; }

    // Scoped to this client's own mailbox: a wrong-recipient ack is structurally incapable
    // of removing anything (section 3.3). Unknown ids are idempotent no-ops (section 3.4).
    session.ack(ack.messageId());
    sendOrDrop(conn, new Frame.AckOk(ack.messageId()));
}

/** Pumps, then closes any slow connection OUTSIDE the session lock. */
private void pump(ClientSession session) {
    ClientConnection slow = session.pump();
    if (slow != null) slow.close("outbound queue full: " + ErrorCode.SLOW_CONSUMER);
}
```

`REGISTER` gains two lines: report the real backlog, then deliver it.

```java
sendOrDrop(connection, new Frame.Registered(clientId, session.pendingCount()));
pump(session);
```

And `onDisconnect` gains one: `session.requeueInflight()` before detaching.

## 7. Gotchas & pitfalls

1. **Requeue in reverse.** Iterating inflight forward and calling `addFirst` each time *reverses* the batch. The test for this must use at least three messages — two would pass by luck.
2. **Never close a connection under the session lock.** `close` waits up to 500 ms for the writer to drain. The pump returns the connection; the caller closes it.
3. **Never write to a socket in the pump.** Only `offer`. This is where STORY-2's isolation property is preserved or destroyed.
4. **Inflight counts against the bound.** Otherwise a client that never acks grows the mailbox without limit while `size()` looks healthy.
5. **`requeueInflight` must run before detaching**, or the pump sees a null connection and the messages stay stranded in inflight.
6. **Payload length is bytes, not `String.length()`.** A multi-byte character makes those differ, and the bound is a byte bound. `payload.getBytes(UTF_8).length`.
7. **Do not enqueue under the sender's lock.** The recipient's session lock guards the recipient's mailbox. Locking both invites deadlock the moment two clients send to each other simultaneously — take one lock at a time, never both.
8. **`ACCEPTED` is sent before the pump runs.** The sender's confirmation must not depend on the recipient being reachable.
9. **Delete `NOT_IMPLEMENTED` and `INVALID_ACK`** when done, and update `OVERVIEW.md`'s semantics table.

## 8. Acceptance Criteria

- [ ] Online recipient receives a `DELIVER` after the sender gets `ACCEPTED`.
- [ ] `ACK` removes the message; the mailbox is then empty.
- [ ] Messages sent to an offline client accumulate and are delivered on reconnect. *(Req 5, 6)*
- [ ] A delivered-but-unacked message is redelivered after reconnect. *(Req 7)*
- [ ] `REGISTERED` reports the real pending count on reconnect.
- [ ] An ack from a client that is not the recipient removes nothing; the message is still redelivered.
- [ ] Acking the same id twice succeeds both times; acking an unknown id succeeds and changes nothing.
- [ ] Unknown recipient → `REJECTED` / `UNKNOWN_RECIPIENT`.
- [ ] Duplicate live message id → `REJECTED` / `DUPLICATE_MESSAGE_ID`.
- [ ] Mailbox at capacity → `REJECTED` / `MAILBOX_FULL`; the count does not grow.
- [ ] Oversized payload → `REJECTED` / `PAYLOAD_TOO_LARGE`; **the connection survives**.
- [ ] `SEND` from an unregistered connection → `ERROR` / `NOT_REGISTERED`.
- [ ] Requeued inflight messages return to the head, in original order, ahead of newer ones.
- [ ] **A recipient that has stopped reading does not delay an unrelated pair of clients.**
- [ ] `NOT_IMPLEMENTED` and `INVALID_ACK` no longer exist.

## 9. Tests to write

**`MailboxTest`** — pure, no sockets, no threads. **Seven, as built:**

| Test | Asserts |
|---|---|
| `offerThenTakeForDeliveryMovesToInflight` | The basic transition; delivered is still retained |
| `ackRemovesOnlyThatMessage` | Unknown and repeated ids are harmless no-ops |
| `requeueInflightRestoresOriginalOrderAtTheFront` | **Three messages** — the reverse-iteration trap |
| `requeuedMessagesPrecedeNewerOnes` | Requirement 7 meeting FIFO: an older redelivery beats a newer accept |
| `rejectsWhenFull` | Bound counts pending + inflight; delivering frees nothing, acking does |
| `rejectsDuplicateLiveId` | Duplicate while pending *and* while inflight; reusable once acked |
| `returnToFrontKeepsMessagePending` | The slow-consumer path puts it back at the head |

**`DeliveryTest`** — end-to-end over sockets. **Thirteen, as built:**

| Test | Asserts |
|---|---|
| `deliverThenAckEmptiesMailbox` | The happy path, requirements 2-4 |
| `offlineRecipientAccumulatesAndReceivesOnReconnect` | Requirements 5 and 6 |
| `unackedMessageIsRedeliveredAfterReconnect` | **Requirement 7 — the one that matters** |
| `takeoverRequeuesUnackedMessages` | The `attach` requeue path, which the original spec missed |
| `ackFromWrongClientRemovesNothing` | And it is still redelivered afterwards |
| `repeatedAndStaleAcksSucceed` | Idempotency |
| `unknownRecipientRejected` | `UNKNOWN_RECIPIENT` |
| `duplicateMessageIdRejected` | And reusable once acked |
| `mailboxFullRejected` | `MAILBOX_FULL`; the count does not grow |
| `oversizedPayloadRejectedConnectionSurvives` | The frame/payload asymmetry over a real socket |
| `payloadLimitIsMeasuredInBytes` | Rocket emoji: 100 chars, 400 bytes — `String.length()` would have passed it |
| `sendFromUnregisteredConnectionRejected` | `NOT_REGISTERED` |
| `slowRecipientDoesNotDelayUnrelatedClients` | **The money test** — see below |

> The spec originally planned one parameterised `sendFailuresReportTheDocumentedCode`.
> Built as five separate tests instead: each failure has a different *setup* (offline
> recipient, prior send, unregistered connection), so parameterising would have meant a
> switch inside the test body — more code, less clarity.

### The test that earns the most

```java
@Test
void slowRecipientDoesNotDelayUnrelatedClients() {
    // bob registers and then stops reading entirely.
    // alice floods bob until bob's outbound queue fills and the server drops him.
    // Throughout, carol -> dave must keep working within a tight deadline.
    //
    // This is the brief's "a slow, malformed, or disconnected client does not block
    // unrelated clients", demonstrated rather than asserted. It is the first test in the
    // project that proves the two-thread design was worth it.
}
```

Assert two things: `dave` receives `carol`'s message inside a short timeout *while alice is
still flooding*, and `alice` keeps receiving responses rather than blocking.

## 10. Manual verification

```bash
mvn clean verify
```

Then the three-terminal demo, which becomes possible for the first time here — though
without the CLI (STORY-5) it needs a scratch client. The beat sheet lives in
[ROADMAP STORY-5](ROADMAP.md); beats 8 and 9 are the ones to watch.

## 11. Out of scope

| Not in this story | Owned by |
|---|---|
| Formal FIFO guarantees under concurrent senders | STORY-4 |
| `RelayClient`, CLI, the demo | STORY-5 |
| Shaded jar, Docker, CI, README/APPROACH | STORY-6 |
| Ack timeout and redelivery timer | Documented next step; not planned |
| Bounded LRU of acked ids for full duplicate detection | Documented limitation; not planned |
| Persistence | STORY-7 (stretch) |

## 11a. Deviations from the original spec

Recorded so this document matches the code.

| # | Spec said | Built instead | Why |
|---|---|---|---|
| 1 | `boolean offer(Message)` | `EnqueueResult offer(Message)` returning an enum | A boolean cannot distinguish `MAILBOX_FULL` from `DUPLICATE_MESSAGE_ID`, and the sender needs to be told which. |
| 2 | Capacity checked before duplicate | Duplicate checked first | A resend of a message already in the mailbox should be told it is a duplicate, not that the mailbox is full. |
| 3 | Only `detach` requeues inflight | `attach` requeues as well | A **takeover** strands inflight messages exactly as a disconnect does — the evicted connection is never coming back to ack them. Covered by `takeoverRequeuesUnackedMessages`. |
| 4 | `public final class Mailbox` | package-private | Nothing outside `session` needs it, and keeping it package-private is what lets the tests use its raw API without widening visibility. |
| 5 | Nothing about a `SEND` with no `messageId` | `MALFORMED_FRAME` and the connection closes | A `REJECTED` frame is *keyed* by `messageId`, so there is literally nothing to answer with. The peer is not speaking the protocol. Same for an `ACK` with no id. |
| 6 | One parameterised failure test | Five separate tests | Each failure needs a different setup; parameterising would have put a switch inside the test body. |
| 7 | `§5` listed 8 files | 11 changed | `ClientRegistry`, `Main` and two existing test classes all needed the config-carrying constructors. |
| 8 | — | `ClientSession.inflightCount()` / `mailboxSize()` added | Needed by tests and by the server log line that makes the demo readable. |

**Also completed here:** `OVERVIEW.md`'s semantics table and error-code list, which still
promised `INVALID_ACK`. Both now describe the structural-ownership behaviour.

**Still outstanding:** ADR-005 (delivery semantics) is not yet written.

## 12. Definition of Done

- [ ] Compiles (`mvn clean compile`)
- [ ] All tests green (`mvn test`), run 5× for flakiness
- [ ] Every acceptance criterion met
- [ ] `OVERVIEW.md` semantics table updated (no more `INVALID_ACK`)
- [ ] ADR-005 written: delivery semantics and the structural-ownership decision
- [ ] Sections 13 and 14 added, as in STORY-1 and STORY-2
- [ ] PR opened summarising what / files / deviations / verification

---

## 13. Walkthrough essentials

### What was introduced

| Thing | What it is | Why it exists |
|---|---|---|
| **`Message`** | `id`, `from`, `payload` | The id is **client-supplied**, so a client can retry a send idempotently and the server uses it as the ack key |
| **`Mailbox`** | `pending` deque + `inflight` map, bounded across both | Delivered and acknowledged are different states — requirement 7 depends on tracking them separately |
| **`ClientSession.pump()`** | Drains pending to the attached connection | Where delivery happens without ever touching a socket |
| **`ClientSession.ack()`** | Removes from *this* session's mailbox | Makes wrong-recipient acks structurally impossible |
| **`RelayService.handleSend`** | Validate → bound → enqueue → `ACCEPTED` → pump | Requirements 2, 3, 5 |
| **`RelayService.handleAck`** | Idempotent removal, always `ACK_OK` | Requirement 4 |
| **Deleted** | `NOT_IMPLEMENTED`, `INVALID_ACK` | Scaffolding gone; ownership is structural so the code became unreachable |

### The message lifecycle

```
                 SEND
                  │
          ┌───────▼────────┐  bound / duplicate / unknown recipient
          │   validated    │──────────────▶ REJECTED  (terminal)
          └───────┬────────┘
                  │ ACCEPTED to sender
          ┌───────▼────────┐
          │    pending     │◀──────────────┐
          └───────┬────────┘               │
                  │ pump → DELIVER         │ requeue to HEAD
          ┌───────▼────────┐               │ (disconnect or takeover)
          │    inflight    │───────────────┘
          └───────┬────────┘
                  │ correct recipient ACKs
              removed  (terminal)
```

Four states, two terminal. Draw this from memory and almost any delivery question is
answerable.

### The five sentences that carry the most weight

1. **"`ACCEPTED` means it is in the recipient's mailbox — not delivered, not read."** That is why the brief lists "confirm the send" and "the recipient acknowledges" as two separate requirements: two different parties confirming two different things.
2. **"Delivered and acknowledged are different states, so they are different structures."** A single list with a cursor breaks the moment an out-of-order ack removes from the middle.
3. **"A wrong recipient cannot ack someone else's message because it never holds a reference to that mailbox."** Structural, not a validation that could be dropped in a refactor.
4. **"At-least-once, with redelivery on reconnect."** State the qualifier — there is no ack timeout, and an interviewer will look for that gap.
5. **"The sender's thread runs the recipient's pump, and that is safe because `offer` never blocks."** It puts a frame on a bounded queue and returns; the recipient's own writer does the socket write.

### Three things to physically point at

- **`Mailbox.requeueInflight`** — the reverse iteration. Walking forwards while calling `addFirst` reverses the batch; the test uses three messages because two would hide it.
- **`ClientSession.pump`** — returns a connection for the *caller* to close, rather than closing it under the lock. Same pattern as `attach` returning the evicted connection, and for the same reason.
- **`DeliveryTest.slowRecipientDoesNotDelayUnrelatedClients`** — the first test in the project that *demonstrates* the concurrency property rather than asserting it.

## 14. Questions to be able to answer

| # | Question | Answer anchor |
|---|---|---|
| 1 | Walk me through Alice sending Bob a message. | Validate → bound-check payload → find bob → enqueue under bob's lock → `ACCEPTED` to alice → pump bob. If bob is offline the pump is a no-op and the message waits. |
| 2 | What does `ACCEPTED` actually promise? | In bob's mailbox. Not delivered, not read. That is why accept and acknowledge are separate requirements. |
| 3 | Why two structures rather than one list? | Acks arrive out of order, so a cursor breaks the moment one removes from the middle. `LinkedHashMap` gives O(1) ack lookup *and* insertion order for requeue. |
| 4 | How does an unacked message survive a disconnect? | `detach` requeues inflight to the **head** of pending before clearing the connection. Reconnect pumps it again. Requirement 7. |
| 5 | What are your delivery guarantees? | At-least-once, **with redelivery on reconnect**, per-recipient FIFO in server-acceptance order. Not exactly-once — across a socket drop the server cannot know whether the client processed it. |
| 6 | What stops the wrong client acking a message? | The ack looks up only the acker's own mailbox. It is structurally incapable of touching another, so there is no check to forget. |
| 7 | Why is a repeated ack not an error? | At-least-once *guarantees* double acks: ack, connection drops before it lands, reconnect, redelivered, ack again. Erroring would punish correct behaviour. |
| 8 | Mailbox full — why reject rather than evict the oldest? | Evicting would silently discard a message we already told the sender was `ACCEPTED`, breaking that promise. Rejecting tells the sender something actionable. |
| 9 | Whose thread runs the pump, and why is that safe? | The sender's, under the recipient's lock. Safe only because `offer` never blocks — it hands a frame to a bounded queue and returns. |
| 10 | Why not lock sender and recipient together? | Deadlock the moment two clients send to each other simultaneously. Only the recipient's lock is ever taken. |
| 11 | What happens if the recipient is offline? | The pump returns immediately; messages accumulate in the mailbox up to the bound. Requirement 5. |
| 12 | Why is inflight counted against the mailbox bound? | It is retained state. Otherwise a client that never acks grows the mailbox without limit while the pending count looks healthy. |
| 13 | What if the recipient stops reading entirely? | Its outbound queue fills, the pump's `offer` fails, the message goes back to pending and the connection is dropped as a slow consumer. Its session and mailbox survive. |
| 14 | How long do you remember a message id? | Only while it is live. Once acked and evicted the id is reusable. Unbounded dedupe history is a memory leak; a bounded LRU is the next step. |
| 15 | Why does a `SEND` with no `messageId` close the connection? | A `REJECTED` frame is *keyed* by `messageId` — there is nothing to answer with. The peer is not speaking the protocol, so it is treated like any malformed frame. |
| 16 | Why measure the payload in bytes rather than characters? | The bound is a byte bound, and a multi-byte character makes `String.length()` differ. There is a test using rocket emoji that `length()` would have let through. |
| 17 | Why does an oversized payload keep the connection but an oversized frame kill it? | The frame parsed fine, so the stream is still in sync and only the message is refused. A frame-size breach means we can no longer locate the next boundary. |
| 18 | What happens to inflight messages on a takeover? | `attach` requeues them too. Otherwise they would be stranded inflight to an evicted connection with nobody left to ack them. |
| 19 | Can acks arrive out of order? | Yes — delivery is pipelined, so several messages are inflight at once. That is why inflight is a map, not a queue. Only *delivery* order is promised. |
| 20 | What is not covered by your tests? | No multi-JVM, no real network partitions, no load or soak testing, and no clock manipulation, so nothing about time-based expiry. |
| 21 | What would you do next? | Ack timeout with bounded retries; a bounded LRU for full duplicate detection; idle-session expiry; persistence; metrics on queue depth and mailbox size. In that order. |
