# STORY-4 — FIFO ordering guarantee

| | |
|---|---|
| **Epic** | EPIC-2 — Guarantees and tooling |
| **Priority** | P1 (bonus 1 of 3) |
| **Points** | 3 |
| **Status** | Done — 82 tests green, mutation-verified |
| **Depends on** | STORY-3 (a mailbox to order, and delivery to observe) |

## 1. Goal

Make per-recipient FIFO ordering **explicit, tested, and precisely documented** — including
under concurrent senders and across a reconnect.

The honest position going in: **the implementation is very likely already correct.** The
mailbox has been an `ArrayDeque` since the first commit precisely so this story would be
cheap, and `MailboxTest` already covers requeue ordering. This story is therefore mostly
about *proving* and *stating* a guarantee rather than building one.

That is not a reason to skip it. An untested ordering claim is worth nothing in an
interview, and the exact wording of the guarantee — what is promised and what deliberately
is not — is more valuable than the code.

## 2. Why this matters

This is bonus 1 of the brief's three, and the cheapest of them. But its real value is that
it forces the sharpest sentence in the whole submission:

> *"FIFO per recipient, in server-acceptance order. Not sender order — with concurrent
> senders there is no meaningful global order, and I am not going to claim one."*

Most candidates will either say "yes, FIFO" without qualification, or not mention ordering
at all. Naming the precise boundary of the guarantee — and being able to say why a stronger
claim would be dishonest — is the kind of answer the brief is asking for when it says
*"define and document your behaviour for message ordering."*

## 3. Concepts & Theory

### 3.1 What order actually means here

Three candidate definitions, only one of which is defensible:

| Definition | Verdict |
|---|---|
| **Sender wall-clock order** — messages arrive in the order senders *sent* them | ✗ Unknowable. Two clients on different machines have no shared clock, and network delay is not uniform. We would be inventing an order we cannot observe. |
| **Global total order** — one order across every message in the system | ✗ Meaningless here. Alice→Bob and Carol→Dave are independent; forcing them into one sequence would mean serialising unrelated work for no benefit. |
| **Per-recipient, server-acceptance order** ✅ | Observable, total *within one mailbox*, and exactly what the mailbox already provides. |

**Server-acceptance order** is the moment the server enqueues the message and answers
`ACCEPTED`. That instant is a real, observable event with a real order, because enqueueing
happens under the recipient's session lock — so two concurrent sends to the same recipient
are serialised by that lock, and whichever wins is genuinely first.

The lock is doing double duty here, and it is worth noticing: it protects the mailbox
*and* it is what makes acceptance order well-defined at all.

### 3.2 Why concurrent senders do not break it, and what they do not get

```
   alice ──┐                      ┌─────────────────────┐
           ├──▶ recipient lock ──▶│ bob's pending deque │──▶ delivery
   carol ──┘     (serialises)     └─────────────────────┘
```

Two senders hammering the same recipient interleave in *some* order. Both of these hold:

- **Every message arrives exactly once, in the order the server accepted it.**
- **Each sender's own messages arrive in that sender's order** — because a single client's frames arrive on one TCP connection, read by one reader thread, in order.

What is explicitly **not** promised: any particular interleaving *between* senders. If
alice and carol both send at the same instant, either may be accepted first. That is not a
weakness to hide — it is the honest consequence of there being no global clock.

### 3.3 The interaction with redelivery

This is where FIFO gets interesting, and where a naive implementation quietly breaks.

```
   accepted:   m1  m2  m3          (all three accepted, in that order)
   delivered:  m1  m2  m3          (all three inflight)
   bob acks:       m2              (out of order - permitted)
   bob drops.
   alice sends m4.

   WRONG (requeue to tail):   m4  m1  m3     ← newer message overtakes older ones
   RIGHT (requeue to head):   m1  m3  m4     ← acceptance order preserved
```

Requeueing to the **head**, in original relative order, is what keeps the guarantee true
across a disconnect. Requeueing to the tail would still deliver everything — so every
happy-path test would pass — while silently violating the ordering claim.

**And the trap inside the trap:** iterating the inflight map forwards while calling
`addFirst` on each element *reverses the batch*. Two messages would hide it; three expose
it. `MailboxTest.requeueInflightRestoresOriginalOrderAtTheFront` uses three for exactly
this reason.

### 3.4 What ordering does *not* cover

State these explicitly, because each is a plausible interviewer follow-up:

| Question | Answer |
|---|---|
| Do acks have to be in order? | No. Delivery is pipelined, so several messages are inflight at once and acks may arrive in any order. Only *delivery* order is promised. |
| Is order preserved across different recipients? | No, and there is nothing to preserve — mailboxes are independent. |
| Is order preserved if a message is rejected? | Rejection means nothing was enqueued, so it never entered the order. |
| Does a slow consumer being dropped break order? | No. The undelivered message goes back to the head via `returnToFront`, and its mailbox survives the disconnect. |
| Is it FIFO if the same client is connected twice? | The second connection takes over, `attach` requeues, and delivery resumes in acceptance order. |

## 4. Design & Approach

**Expected code change: little or none.** The design decisions that produce FIFO were all
made earlier:

| Property | Where it already comes from |
|---|---|
| New messages go to the back | `Mailbox.offer` → `pending.addLast` |
| Delivery takes from the front | `Mailbox.takeForDelivery` → `pending.pollFirst` |
| Requeue goes to the front, in order | `Mailbox.requeueInflight` → reverse iteration + `addFirst` |
| A refused delivery keeps its place | `Mailbox.returnToFront` |
| Concurrent sends are serialised | The recipient's `ReentrantLock` |
| One sender's messages stay in order | One TCP connection, one reader thread |

So the work is: **write the tests that would fail if any of those were wrong**, then write
the guarantee down where it will be read.

Approach the tests adversarially. For each row above, ask "what would break this, and does
a test currently notice?" The `returnToFront` row is the weakest — it is covered in
`MailboxTest` but never observed end-to-end through a socket.

### If a test does fail

Good — that is the story earning its place. The most likely culprits, in order:

1. `returnToFront` interacting with a partially-drained pump (message order after a slow-consumer drop).
2. Concurrent `enqueue` racing the pump if any path ever enqueues outside the session lock.
3. An `attach`-time requeue landing in the wrong place relative to messages accepted while offline.

## 5. Files to create / modify

| Path | Purpose |
|---|---|
| `test/.../OrderingTest.java` | The new end-to-end ordering tests |
| `test/.../ClientSessionTest.java` | **Created (not foreseen).** The pump and the slow-consumer path, against a fake connection — where the head-vs-tail regression is actually caught |
| `test/.../MailboxTest.java` | **Modified:** one test added for requeue with a *non-empty* pending queue |
| `test/.../DeliveryTest.java` | **Modified:** corrected a comment claiming the slow client was dropped by queue overflow; it is dropped by a socket write failure |
| `docs/adr/ADR-005-delivery-semantics.md` | **Create.** Delivery guarantees, structural ack ownership, and this ordering statement — the ADR STORY-3 deferred |
| `docs/OVERVIEW.md` | **Verify**, do not assume: the semantics table already claims per-recipient FIFO. Make sure it says *server-acceptance* order. |
| `session/Mailbox.java` | **Untouched, as predicted.** No production code changed in this story. |

## 6. Implementation

Mostly tests. The one thing worth writing carefully is the concurrent-send test, because a
badly built one is either flaky or vacuous.

### 6.1 Sequential ordering, end to end

```java
@Test
@DisplayName("100 sequential sends arrive in acceptance order")
void sequentialSendsArriveInOrder() throws IOException {
    TestClient alice = registered("alice");
    TestClient bob = registered("bob");

    for (int i = 1; i <= 100; i++) {
        alice.send(new Frame.Send("m" + i, "bob", "payload " + i));
        alice.expect(Frame.Accepted.class);          // acceptance order == send order here
    }
    for (int i = 1; i <= 100; i++) {
        assertEquals("m" + i, bob.expect(Frame.Deliver.class).messageId());
    }
}
```

> Note `maxMailboxMessages` must be ≥ 100 in this test's config, and
> `outboundQueueCapacity` ≥ 100 as well, or bob is dropped as a slow consumer mid-run. Use
> a dedicated config rather than reusing `DeliveryTest`'s deliberately tiny one.

### 6.2 Concurrent senders

```java
@Test
@DisplayName("concurrent senders: every message arrives once, each sender's own in order")
void concurrentSendersKeepTheirOwnOrder() throws Exception {
    TestClient bob = registered("bob");
    var start = new CountDownLatch(1);      // release both senders together

    // Two senders, 50 messages each, ids prefixed by sender so they can be separated.
    // Deliberately NOT asserting how they interleave - there is no honest claim to make.
    ...
    start.countDown();

    List<String> received = collect(bob, 100);

    assertEquals(100, Set.copyOf(received).size(), "every message arrives exactly once");
    assertEquals(idsFor("alice"), onlyFrom(received, "alice"), "alice's own order preserved");
    assertEquals(idsFor("carol"), onlyFrom(received, "carol"), "carol's own order preserved");
}
```

The shape that matters: **filter the received list per sender and assert order within each
filter.** Asserting anything about the interleaving would be asserting something the design
deliberately does not promise, and such a test would be flaky by construction.

Use a `CountDownLatch` to release both senders at once — that is what actually creates
contention on the recipient's lock. Without it the two senders trivially serialise and the
test proves nothing.

### 6.3 Ordering across a reconnect

```java
@Test
@DisplayName("redelivered messages precede ones accepted while offline")
void redeliveryPreservesAcceptanceOrder() throws IOException {
    // deliver m1 m2 m3, ack only m2, drop bob, accept m4 while offline, reconnect.
    // Expect: m1, m3, m4  - the unacked pair first, in their original order.
    // Requeue-to-tail would give m4, m1, m3 and pass every happy-path test.
}
```

**Correction, found by mutation after implementation.** This test does *not* catch a
requeue-to-tail regression, despite reading as though it should. At the moment of requeue
its pending queue is **empty** — all three messages are inflight — and on an empty deque
`addFirst` and `addLast` are the same place. It passed against a deliberately broken
implementation, as did three other ordering tests.

The distinction only shows up when pending is **non-empty** at requeue time, which happens
only when delivery stopped part-way — i.e. the recipient's outbound queue refused a frame.
Two tests cover that, and they are the ones that actually hold the guarantee up:
`MailboxTest.requeuedMessagesGoAheadOfAlreadyPendingOnes` and
`ClientSessionTest.refusedMessageKeepsItsPosition`.

This test is still worth keeping — it is the readable end-to-end statement of requirement 7
meeting FIFO, and it is the scenario to describe out loud. It is just not the safety net.

## 7. Gotchas & pitfalls

1. **A vacuous concurrency test is worse than none.** Without a latch releasing senders together, there is no contention and the test proves nothing. (This project has already shipped one vacuous test — see STORY-3's slow-client config — so check the test can fail.)
2. **Do not assert an interleaving between senders.** It is not promised, and such a test will be flaky.
3. **Reusing `DeliveryTest`'s config will break these tests**, and misleadingly: its `maxMailboxMessages = 20` and `outboundQueueCapacity = 4` exist to make *drops* easy, which is the opposite of what ordering tests need.
4. **Three messages minimum** for any requeue-ordering assertion. Two would pass with a reversed batch.
5. **Collecting N frames needs a bound.** Use `assertTimeoutPreemptively` around the collection loop, not an unbounded read.
6. **Do not over-claim in the docs.** "FIFO" alone is wrong. It is per-recipient, in server-acceptance order.

## 8. Acceptance Criteria

- [x] 100 sequential sends to one recipient arrive in acceptance order.
- [x] Two senders sending concurrently to one recipient: every message arrives exactly once.
- [x] Each sender's own messages arrive in that sender's order.
- [x] No test asserts an interleaving *between* senders.
- [x] Redelivered messages arrive ahead of messages accepted while offline, in original order.
- [x] Out-of-order acks do not disturb the order of what remains.
- [x] A message returned to the queue after a slow-consumer drop keeps its position.
- [x] The guarantee is stated precisely in `OVERVIEW.md` and `ADR-005` as **per-recipient FIFO in server-acceptance order**, with the non-guarantees named.
- [x] Full suite green, run 5× for flakiness.

## 9. Tests to write

**`OrderingTest`** (sockets) — 5:

| Test | Asserts |
|---|---|
| `sequentialSendsArriveInOrder` | 100 messages, one sender, acceptance order |
| `concurrentSendersKeepTheirOwnOrder` | Exactly-once + per-sender order; **no** interleaving assertion |
| `redeliveryPreservesAcceptanceOrder` | Requirement 7 meeting FIFO, end to end |
| `acksNeedNotBeInOrder` | Scrambled acks empty the mailbox |
| `takeoverResumesInAcceptanceOrder` | The `attach`-requeue path |

**`MailboxTest`** — 1 added (8 total):

| Test | Asserts |
|---|---|
| `requeuedMessagesGoAheadOfAlreadyPendingOnes` | **Head vs tail — the only unit test that distinguishes them** |

**`ClientSessionTest`** (new, pure, fake connection) — 4:

| Test | Asserts |
|---|---|
| `pumpDeliversInOrder` | The pump drains in order and leaves messages inflight |
| `pumpReturnsConnectionWhenQueueRefuses` | Returned, not closed — closing under the lock would stall the identity |
| `refusedMessageKeepsItsPosition` | **Catches requeue-to-tail**; the socket-level slow-consumer case |
| `pumpWhileOfflineIsANoOp` | Messages wait, nothing is lost |

Ten tests across three classes. The slow-consumer path is covered **at session level, not
over a socket**, deliberately: OS send and receive buffers (~64 KiB each) absorb thousands
of small frames before the application queue backs up, so a socket-level version would
depend on platform buffer sizes and be flaky. A fake connection that refuses offers
reproduces the same condition exactly, in microseconds.

## 10. Manual verification

```bash
mvn clean verify
```

Deferred to STORY-5, where the CLI makes ordering visible: send five messages to an offline
client, reconnect, and watch them arrive in order in the terminal. That is also the most
convincing thirty seconds of the demo after requirement 7.

## 11. Out of scope

| Not in this story | Owned by |
|---|---|
| Any ordering guarantee *between* recipients | Not promised; documented as such |
| Ack ordering | Not promised; delivery order only |
| Total order across the system | Explicitly rejected in §3.1 |
| `RelayClient`, CLI, the demo | STORY-5 |
| Docker, CI, README/APPROACH | STORY-6 |

## 11a. Deviations from the original spec

| # | Spec said | What happened | Why |
|---|---|---|---|
| 1 | `redeliveryPreservesAcceptanceOrder` is "the one that would actually catch a regression" | **It does not.** It passed against a deliberately broken (requeue-to-tail) implementation | Its pending deque is *empty* at the moment of requeue, and on an empty deque `addFirst` and `addLast` are the same place. Three other ordering tests had the same blind spot. |
| 2 | 5 tests in one new class | 10 tests across three classes, one of them new (`ClientSessionTest`) | The head-vs-tail case needs a *non-empty* pending queue, which only happens when the outbound queue refuses a frame — reachable at session level, not over a socket. |
| 3 | Cover `returnToFront` "end-to-end through a socket" | Covered at **session level** instead, with a fake connection | OS send/receive buffers (~64 KiB each) absorb thousands of small frames before the application queue backs up, so a socket-level version would depend on platform buffer sizes and be flaky. |
| 4 | *(nothing)* | `DeliveryTest`'s slow-client comment corrected | It claimed bob was dropped by queue overflow. He is dropped by a **socket write failure**. The test still proves isolation, which is its name; the comment overstated the mechanism. |
| 5 | "Only if a test fails" for `Mailbox.java` | **No production code changed at all** | As predicted in §4 — every mechanism producing FIFO was already in place from STORY-3. |
| 6 | Sequential test uses `DeliveryTest`-style config | A dedicated config with `maxMailboxMessages = 500`, `outboundQueueCapacity = 500` | Reusing the tiny one would have dropped bob as a slow consumer mid-run. Flagged as gotcha 3 and it was a real risk. |

## 12. Definition of Done

- [x] Compiles (`mvn clean compile`)
- [x] All tests green (`mvn test`), run 5×
- [x] Every acceptance criterion met
- [x] **Each new test verified capable of failing** — break the ordering deliberately once and confirm it goes red
- [x] ADR-005 written (delivery semantics, structural ack ownership, ordering)
- [x] `OVERVIEW.md` ordering wording checked, not assumed
- [x] Sections 13 and 14 added
- [ ] PR opened summarising what / files / deviations / verification *(solo repo — no PR flow in use)*

---

## 13. Walkthrough essentials

### What was introduced

**No production code changed in this story.** That is the headline, and it is worth saying
out loud: every mechanism that produces FIFO was already in place, because the mailbox has
been an `ArrayDeque` since the first commit. This story added proof and wording.

| Thing | What it is | Why it exists |
|---|---|---|
| **`OrderingTest`** | 5 socket-level tests | The guarantee, demonstrated end to end |
| **`ClientSessionTest`** | 4 pure tests against a fake connection | The pump and the slow-consumer path, where the real regression is caught |
| **`MailboxTest` +1** | `requeuedMessagesGoAheadOfAlreadyPendingOnes` | The **only** test that distinguishes requeue-to-head from requeue-to-tail |
| **`ADR-005`** | Delivery semantics, ack ownership, ordering | The document `APPROACH.md` will draw on |

### The guarantee, stated exactly

```
GUARANTEED                                  NOT GUARANTEED
──────────                                  ──────────────
Per-recipient FIFO, in server-              Any interleaving BETWEEN concurrent
  acceptance order                            senders
Each sender's own messages keep             Ordering across different recipients
  that sender's order                       Ack ordering
Redelivered messages precede
  messages accepted later
```

**Acceptance order** is the instant the server enqueues and answers `ACCEPTED`. It is
well-defined because enqueueing happens under the recipient's session lock — so two
concurrent sends to the same recipient are serialised by that lock, and whichever wins
genuinely was first.

### The five sentences that carry the most weight

1. **"FIFO per recipient, in server-acceptance order — not sender order."** With concurrent senders on different machines there is no shared clock, so a stronger claim would be inventing an order I cannot observe.
2. **"The session lock does double duty."** It protects the mailbox *and* it is what makes "first" meaningful at all.
3. **"Redelivery requeues to the head, in original order."** To the tail would still deliver everything — every happy-path test would pass — while silently breaking the guarantee.
4. **"Each sender's own order is preserved for free."** One client, one TCP connection, one reader thread.
5. **"I mutated the implementation to check my ordering tests could fail — and four of them couldn't."** See below.

### The thing to actually tell them

This is the strongest story in the submission, and it is short:

> *"The definition of done for that story said each new test had to be verified capable of
> failing. So I changed the mailbox to requeue at the tail instead of the head and re-ran.
> Only one test went red. Four ordering tests passed against a deliberately broken
> implementation — because in all of them the pending queue was **empty** at the moment of
> requeue, and on an empty deque head and tail are the same place. I added a test for the
> non-empty case."*

It demonstrates that the tests are load-bearing rather than decorative, and that the
verification discipline is real rather than claimed.

### Three things to physically point at

- **`MailboxTest.requeuedMessagesGoAheadOfAlreadyPendingOnes`** — the comment explains why every *other* ordering test is blind to this.
- **`OrderingTest.concurrentSendersKeepTheirOwnOrder`** — the `CountDownLatch` that creates real contention, and the deliberate absence of any interleaving assertion.
- **`ClientSessionTest`** — no sockets at all. The payoff for keeping `session` free of `java.net` back in STORY-2.

## 14. Questions to be able to answer

| # | Question | Answer anchor |
|---|---|---|
| 1 | What is your ordering guarantee? | Per-recipient FIFO, in server-acceptance order. |
| 2 | Why acceptance order rather than the order senders sent? | Two clients share no clock and delay is not uniform — sender order is unobservable, so claiming it would be inventing something. |
| 3 | What makes acceptance order well-defined? | Enqueueing happens under the recipient's session lock, so concurrent sends to one recipient are serialised and whichever wins genuinely was first. |
| 4 | What do you *not* guarantee? | Interleaving between concurrent senders, ordering across recipients, and ack ordering. Naming these is the point. |
| 5 | How does ordering survive a disconnect? | Unacknowledged messages requeue to the **head** of pending, in original relative order, so they precede anything accepted later. |
| 6 | What would requeueing to the tail do? | Still deliver everything — so every happy-path test passes — while silently reordering. That is the regression the story was written to catch. |
| 7 | How do you know your ordering tests actually work? | I mutated the implementation to requeue at the tail. Only one test failed; four passed. I added one for the case they all missed. |
| 8 | Why were four of them blind to it? | Their pending deque was empty at the moment of requeue. On an empty deque, `addFirst` and `addLast` are the same place. |
| 9 | Do acks have to be in order? | No. Delivery is pipelined so several messages are inflight at once; inflight is a map keyed by id precisely because acks may arrive in any order. |
| 10 | How do you test concurrent senders without flakiness? | Release both senders from a `CountDownLatch` so they genuinely contend, then assert exactly-once and per-sender order — and assert **nothing** about the interleaving, because that is not promised. |
| 11 | Why is the slow-consumer path not tested over a socket? | OS send and receive buffers absorb thousands of small frames before the application queue backs up, so it would depend on platform buffer sizes and be flaky. A fake connection that refuses offers reproduces the condition exactly. |
| 12 | Did FIFO require any code changes? | None. The deque, the head-requeue and the session lock were all already there — the mailbox was designed for this in STORY-3. |
| 13 | What happens to ordering on a takeover? | `attach` requeues what the evicted connection never acknowledged, and delivery resumes in acceptance order. Covered by `takeoverResumesInAcceptanceOrder`. |
| 14 | Is ordering preserved between two different recipients? | No, and there is nothing to preserve — mailboxes are independent, and forcing a global order would serialise unrelated work for no benefit. |
| 15 | Why did the sequential test need its own config? | `DeliveryTest`'s deliberately tiny mailbox and queue exist to make *drops* easy. Reusing it would have dropped the recipient as a slow consumer mid-run. |
| 16 | Where is this documented for someone who never speaks to you? | `ADR-005`, and the semantics table in `OVERVIEW.md`. |
