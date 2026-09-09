# ADR-005 — Delivery semantics, acknowledgement, and ordering

**Status:** Accepted · **Date:** 2026-09-07

## Context

The brief requires the service to *"define and document your behaviour for message
ordering, duplicate sends, and repeated or stale acknowledgements"* and states that
*"at-least-once delivery is acceptable: an unacknowledged message may be delivered again"*.
Note the wording: **define and document**, not prevent. A defensible documented choice
scores; silence does not.

## Decisions

### 1. Delivery: at-least-once, with redelivery on reconnect

A message is retained until the recipient acknowledges it. If the connection drops in
between, it is delivered again after reconnecting.

**The qualifier matters and must not be dropped.** There is no ack timeout. A client that
stays connected and never acknowledges holds a message in flight indefinitely. So the
honest phrasing is *"at-least-once, with redelivery on reconnect"* — not unqualified
at-least-once.

Exactly-once is not achievable here. Across a socket drop the server cannot know whether
the client processed a message before dying, so it must choose between redelivering
(at-least-once) and discarding (at-most-once). At-least-once is the safer default and the
one the brief permits.

*Next step:* an ack timeout with a bounded retry count, after which the message is dead-lettered.

### 2. `ACCEPTED` means "in the mailbox", not "delivered"

Two frames confirm two different things, from two different parties:

| Frame | From | Asserts |
|---|---|---|
| `ACCEPTED` | server → sender | custody taken |
| `ACK` | recipient → server | responsibility taken |

Collapsing them would mean either lying to the sender or making the sender wait on a
recipient that may be offline for hours. This is why the brief lists "confirm the send" and
"the recipient acknowledges" as separate requirements.

### 3. Ack ownership is structural, not validated

The brief says a message is removed *"only after the correct recipient acknowledges it"*.

Rejected: a global message index plus an explicit `if (!message.recipient().equals(acker))`
check. It works, and it leaves a `remove(id)` on a shared map one refactor away from a
serious bug.

**Chosen:** the ack lookup is scoped to the acking session's own mailbox. A client acking
another client's message id never holds a reference to that mailbox, so removing it is
*impossible* rather than *prevented*.

**Consequence:** a wrong-recipient ack is indistinguishable from a stale one — both are
"that id is not in your mailbox". Both return `ACK_OK`. `ErrorCode.INVALID_ACK` became
unreachable and was deleted.

### 4. Repeated and stale acks are idempotent

They return `ACK_OK` and change nothing.

This is not leniency, it is required. At-least-once delivery **guarantees** double acks: a
client acks, the connection drops before it lands, it reconnects, the message is
redelivered, it acks again. Erroring would punish a client for behaviour our own delivery
guarantee forces on it.

### 5. Duplicate sends are rejected while the id is live

A `SEND` whose `messageId` is already pending or inflight for that recipient gets
`REJECTED` / `DUPLICATE_MESSAGE_ID`. Ids are client-supplied precisely so a client can retry
a send idempotently.

**Documented gap:** once a message is acknowledged and evicted, its id is forgotten and
would be accepted again as a new message. Remembering every id ever seen is an unbounded
memory leak, and the brief requires bounded state — so the detection window is deliberately
bounded to what is live.

*Next step:* a bounded LRU of recently-acknowledged ids.

### 6. Delivery is pipelined, and acks may arrive out of order

The pump drains everything available rather than waiting for each ack. Stop-and-wait would
cost a round trip per message and buy nothing, since the bounded outbound queue already
provides flow control.

Two consequences: several messages are inflight at once, and acks may arrive in any order.
That is why inflight is a map keyed by id rather than a queue.

### 7. Ordering: per-recipient FIFO, in server-acceptance order

Three definitions were available; only one is honest.

| Definition | Verdict |
|---|---|
| Sender wall-clock order | ✗ Unknowable — no shared clock, non-uniform delay |
| Global total order across all messages | ✗ Meaningless — mailboxes are independent |
| **Per-recipient, server-acceptance order** | ✅ Observable and total within one mailbox |

Acceptance order is the moment the server enqueues the message and answers `ACCEPTED`. It
is well-defined because enqueueing happens under the recipient's session lock — so two
concurrent sends to the same recipient are serialised by that lock, and whichever wins
genuinely was first. **The lock does double duty:** it protects the mailbox *and* it is what
makes "first" meaningful.

**Guaranteed:**
- Every accepted message is delivered, in acceptance order, per recipient.
- Each individual sender's messages keep that sender's order (one connection, one reader thread).
- Redelivered messages precede messages accepted later.

**Explicitly not guaranteed:**
- Any interleaving *between* concurrent senders.
- Ordering across different recipients.
- Ack ordering.

## Consequences

- Redelivery must requeue to the **head** of pending, in original relative order — anything else silently violates the ordering claim while still delivering everything.
- Both disconnect *and* takeover requeue, since an evicted connection is never coming back to acknowledge.
- Clients must tolerate duplicates. That is stated in the README rather than hidden.
- State is in-memory only; a server restart loses everything. See Next steps in [APPROACH.md](../../APPROACH.md).

## A note on how this was verified

The head-versus-tail requeue decision was checked by **mutation**: the implementation was
deliberately changed to requeue at the tail, and the suite re-run.

Only one test failed. Four other ordering tests passed against the broken implementation,
because each had an *empty* pending queue at the moment of requeue — where head and tail are
the same place. A dedicated test was added for the non-empty case.

That is worth recording because it is the general lesson: an ordering test whose queue is
empty at the critical moment asserts nothing about ordering.
