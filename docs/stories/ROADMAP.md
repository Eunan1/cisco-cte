# Story Roadmap

> **Deliberately thin.** Each entry below is a skeleton: enough to know where the work is
> going and what decisions it will force, not a spec. A story is expanded into the full
> house template **only when the previous story is finished and green**. That way each
> spec is written with the benefit of what the last one taught us.

**Status legend:** `To Do` · `In Progress` · `Done`

| Story | Title | Epic | Pts | Status | Spec |
|---|---|---|---|---|---|
| 1 | Frames on the wire | EPIC-1 | 3 | **Done** | [full spec](STORY-1-frame-codec.md) |
| 2 | Server, connections, and identity | EPIC-1 | 8 | **Done** | [full spec](STORY-2-server-and-sessions.md) |
| 3 | Mailbox, delivery, and acknowledgement | EPIC-1 | 8 | **Done** | [full spec](STORY-3-mailbox-and-acknowledgement.md) |
| 4 | FIFO ordering guarantee | EPIC-2 | 3 | **Done** | [full spec](STORY-4-fifo-ordering.md) |
| 5 | Client, CLI, and demo | EPIC-2 | 3 | **Done** | [full spec](STORY-5-client-cli-demo.md) |
| 6 | Artifact, Docker, CI, and the graded docs | EPIC-3 | 5 | In Review | [full spec](STORY-6-artifact-docker-ci-docs.md) |
| 7 | Persistence across restart | EPIC-4 | 8 | Stretch | skeleton |

**Epics**

- **EPIC-1 — Core relay.** Everything the brief lists as a requirement. Stories 1–3. **The core exercise is complete at the end of STORY-3**; nothing after it is required.
- **EPIC-2 — Guarantees and tooling.** The FIFO bonus and the client you demo with. Stories 4–5.
- **EPIC-3 — Ship it.** The runnable artifact and the two graded markdown files. Story 6.
- **EPIC-4 — Stretch.** Durability. Story 7. Realistically will not be built.

### Why seven stories and not eleven

An earlier draft split this into eleven. That was too many for the time available, and it
front-loaded effort onto the easy, pure parts of the problem. Three changes:

- **Server lifecycle and registration merged** into STORY-2. Attaching a connection to a session is the same piece of thinking as accepting one; splitting them meant designing the seam twice.
- **Mailbox/send and ack/redelivery merged** into STORY-3. Requirement 7 (unacked survives disconnect) cannot be designed without the mailbox in front of you, so separating them was artificial.
- **The "test hardening" story deleted.** Its tests now live in the story that creates the behaviour they test. A test written a week after the code is a worse test.

Cut from the bottom, never the middle: a finished core with honest next steps beats a
half-wired bonus.

---

## STORY-1 — Frames on the wire ✅

**Delivered.** Maven project, `RelayConfig` with all six bounds and environment overrides,
the sealed `Frame` hierarchy with ten records nested, `ErrorCode`, `ProtocolException`, and
`FrameCodec` — 4-byte big-endian length prefix over UTF-8 JSON, bound checked before
allocation, `readFully` for segmentation.

171 lines of main code, 17 test methods, 28 assertions green. Wire format documented in
[PROTOCOL.md](../others/PROTOCOL.md); decisions in [ADR-002](../adr/ADR-002-framing-and-serialisation.md).

**What it taught us, carried into STORY-2:**
- Building the pure piece first worked — there is now no chance of debugging framing and concurrency simultaneously.
- Injecting `maxFrameBytes` rather than reading config statically made the bound testable with a 1 KiB limit. Do the same for every bound in STORY-2.
- Taking the environment lookup as a `UnaryOperator<String>` is what made config testable at all. Same trick wherever the OS is involved.

**Fully specified:** [STORY-1-frame-codec.md](STORY-1-frame-codec.md)

---

## STORY-2 — Server, connections, and identity

**Goal.** A `RelayServer` that binds, accepts, and shuts down predictably; a `Connection`
per socket with its own reader thread, writer thread and bounded outbound queue; and a
`ClientRegistry` of `ClientSession`s where **identity outlives the connection**. Register
and reattach work end to end. No mailbox yet — nothing is queued, nothing is delivered.

**Decisions it forces.**
- Virtual threads vs a fixed pool, and how to justify it.
- What happens when a connection's outbound queue is full: block, drop the frame, or drop the connection.
- The identity/connection seam — `ClientSession` owns state, `Connection` is a nullable field on it. **This is the single most important decision in the project.**
- Takeover policy when a name is claimed while an apparently-live connection holds it.
- What "shut down predictably" means, concretely.
- Whether sessions are ever reclaimed.

**Fully specified:** [STORY-2-server-and-sessions.md](STORY-2-server-and-sessions.md)

---

## STORY-3 — Mailbox, delivery, and acknowledgement

**Goal.** The core exercise, finished. A bounded `Mailbox` with `pending` and `inflight`;
`SEND` validating, bound-checking, enqueuing and answering `ACCEPTED`/`REJECTED`; a
delivery pump; `ACK` removing only when the rightful recipient acks; and requeue on detach
so an unacked message is redelivered after reconnect.

**Decisions it forces.**
- What `ACCEPTED` promises. *(In the mailbox — not delivered, not read.)*
- Mailbox-full policy: reject newest vs evict oldest. *(Leaning: reject newest and tell the sender. Silently discarding a message we already called `ACCEPTED` would break that promise.)*
- Two structures (`pending` deque + insertion-ordered `inflight` map) vs one with a cursor. *(Leaning: two — a cursor breaks the moment an ack removes from the middle.)*
- Stale/repeated ack response. *(Leaning: idempotent `ACK_OK`. At-least-once guarantees double acks happen; erroring punishes correct clients.)*
- Wrong-client ack. *(Decision: scope the ack lookup to the acker's **own** mailbox, so removing someone else's message is **structurally impossible** rather than blocked by a check. Consequence: `INVALID_ACK` becomes unreachable and is deleted — a wrong-recipient ack is indistinguishable from a stale one, and both are idempotent no-ops.)*
- Duplicate `messageId` handling and the bounded-history limitation it implies.
- Pipelined vs stop-and-wait delivery. *(Decision: pipelined. Stop-and-wait costs a round trip per message and buys nothing — the bounded outbound queue already provides flow control. Consequence: several messages inflight at once, acks may arrive out of order, hence a map not a queue.)*
- What triggers redelivery. *(Decision: reconnect only. No ack timeout. So the guarantee is **"at-least-once, with redelivery on reconnect"** — state it that precisely, and name the ack timeout as the next step before an interviewer finds the gap.)*
- Whether inflight counts against the mailbox bound. *(Leaning: yes — it is retained state, and the brief says mailboxes are bounded.)*

**Tests that earn marks here.** Deliver→ack→empty. Offline accumulation then reconnect
delivery. Deliver→drop without acking→reconnect→redelivered. Wrong-client ack rejected and
message still redelivered. Double ack succeeds twice. Mailbox full rejects with the
documented code. Oversized payload rejected but connection survives.

**Watch for.** Never write to the recipient's socket on the sender's thread — hand the
frame to the recipient's outbound queue and return. The mailbox is a **deque from the first
commit** so STORY-4 is nearly free.

---

## STORY-4 — FIFO ordering guarantee (bonus 1)

**Goal.** Make per-recipient FIFO explicit, tested, and documented — including under
concurrent sends and across a reconnect.

**Decisions it forces.**
- The definition: FIFO in **server-acceptance order**, per recipient. There is no honest global order across concurrent senders and claiming one would be wrong.
- Requeue ordering: inflight returns to the *head*, in original relative order.
- Ack ordering is explicitly not guaranteed; only delivery order is.

**Acceptance sketch.** 100 sequential sends arrive in order. Two senders each sending 50
concurrently: every message arrives exactly once and each sender's own messages keep their
order. Deliver 3, ack the first, drop, reconnect: 2 and 3 arrive in that order, ahead of
anything newer.

**Watch for.** The reverse-iteration trap when requeueing (OVERVIEW trap 11). Iterating
inflight forward and calling `addFirst` each time reverses it.

---

## STORY-5 — Client, CLI, and demo

**Goal.** A `RelayClient` used by both the tests and a small interactive CLI, plus the
documented three-terminal demo.

### Runtime shape

```
Terminal 1  SERVER    java -jar relay.jar server        <- the observability window
Terminal 2  CLIENT    java -jar relay.jar client alice
Terminal 3  CLIENT    java -jar relay.jar client bob
```

**One artifact, subcommand-dispatched** (`server` / `client <name>`) rather than two jars —
it keeps "the artifact produced" a single clean answer in the README and makes the two demo
commands nearly identical, which matters when typing under observation.

The server terminal carries the demo: log every registration, accept, queue-depth change,
delivery and ack there, and print the effective config on boot. The interviewer then
watches *state changing* rather than watching you describe it.

**Decisions it forces.**
- **Auto-ack vs manual ack.** *Manual, as an explicit `ack <id>` command.* Auto-acking hides the exact behaviour the exercise is about, and beat 8 below depends on deliberately not acking.
- **Auto-reconnect.** *No.* Keep reconnect explicit so the demo shows it on purpose.
- **Client host config.** Needs `RELAY_HOST` as well as `RELAY_PORT`, or the Docker demo cannot reach the server by container name.

### Demo beat sheet — roughly 90 seconds, covers requirements 1-7

| # | Terminal | Action | What they see |
|---|---|---|---|
| 1 | T1 | start server | bounds printed, listening |
| 2 | T2, T3 | register `alice`, `bob` | `REGISTERED pending=0` twice — *req 1* |
| 3 | T2 | `send bob hello` | `ACCEPTED m1`; T3 shows `DELIVER` — *req 2, 3, 4* |
| 4 | T3 | `ack m1` | server logs mailbox back to 0 |
| 5 | T3 | Ctrl-C | server: "bob detached, session retained" |
| 6 | T2 | `send bob one`, `send bob two` | both `ACCEPTED`; server: "bob offline, 2 pending" — *req 5* |
| 7 | T3 | restart as `bob` | `REGISTERED pending=2`, both delivered in order — *req 6* |
| 8 | T3 | Ctrl-C **without acking** | — |
| 9 | T3 | restart as `bob` | both **redelivered** — *req 7* |

Beats 8 and 9 are what separate this submission from a competent one. Rehearse them.

**Watch for.** The tests use **no terminals** — they run the server in-process on port 0 and
drive `RelayClient` directly. The CLI must be a thin REPL over that same class, so that
"the tests and the demo exercise identical client code" is true when you say it.

---

## STORY-6 — Artifact, Docker, CI, and the graded docs

**Goal.** One runnable jar, one small image, one workflow, and the two documents that are
actually marked.

**Why the artifact work de-risks the interview.** The brief says *"no laptop is needed"* and
the repo *"must reproduce in the interview environment"* — assume an unfamiliar machine
that may lack Java 21 or Maven. Document both paths:

```bash
# Path A - needs Java 21 + Maven (or ./mvnw)
mvn clean package && java -jar target/relay-1.0.0.jar server

# Path B - needs only Docker
docker network create relay-demo
docker run --rm --network relay-demo --name relay-server -p 9090:9090 relay:latest server
docker run --rm -it --network relay-demo -e RELAY_HOST=relay-server relay:latest client alice
```

Use a user-defined bridge network and reach the server by container name. **Avoid
`--network host`** — it behaves differently on macOS and Windows and will fail live.

**Also worth doing here:** add the Maven Wrapper (`mvn wrapper:wrapper`). Maven is not on
PATH on the dev machine, and `./mvnw clean verify` removes a prerequisite rather than
documenting one.

**README must contain** (their words): prerequisites; exact build, run and test commands;
the artifact produced; and how ports, timeouts, dependencies and server lifecycle are
controlled.

**APPROACH must contain** (their words): acceptance criteria; architecture, protocol, state
and concurrency models; delivery semantics; trade-offs; known limitations; next steps; and
**AI-tool usage** — a named deliverable, do not skip it.

**Watch for.** Exec-form `ENTRYPOINT` (OVERVIEW trap 12) — the one that will embarrass you
live if you demo `docker stop`. Bind to `0.0.0.0`, not `localhost`, inside the container.
Known limitations is where you *gain* marks: in-memory only, no auth, no encryption, single
server, unbounded session growth, bounded duplicate detection, no ack timeout. Finish with
a clean-clone verification.

---

## STORY-7 — Persistence across restart (stretch)

**Goal.** Queued and unacked messages survive a server restart.

**Realistically will not be built,** and that is the right call. It cannot start until
STORY-3 exists, it is the largest remaining piece, and a half-finished durability layer is
worse than none — it puts unfinished code in front of an interviewer and invites questions
about recovery semantics that have not been thought through.

**Sketch.** Append-only log of accept / deliver / ack events, replayed on boot to rebuild
mailboxes. The real decisions: fsync policy and the durability it actually buys; log
compaction (an unbounded log is a bounds violation); detecting a partial record after a
crash mid-write; and disk-full behaviour.

**The trap worth naming even if unbuilt:** fsyncing under the session lock puts disk
latency on the send path, so a slow disk starts blocking clients — which contradicts the
"a slow thing does not block unrelated clients" property the whole design is built around.
That tension is the interesting answer.

If it does not ship, write that paragraph into `APPROACH.md` as the next step. A
well-reasoned plan scores nearly as well as a rushed implementation, and the brief
explicitly rewards describing what remains.
