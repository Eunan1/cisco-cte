# Demo script

> Read this, don't improvise. Ninety seconds, nine beats, all seven requirements.
> Beats 8 and 9 are the ones that matter — do not skip them for time.

## Setup — before they join

Three terminals, large font. Build once so nothing compiles on camera:

```bash
./mvnw -q clean package -DskipTests
```

Then one command per terminal — same on every platform:

| Terminal | Command |
|---|---|
| **T1 - server** | `java -jar target/relay-1.0.0.jar server` |
| **T2 - alice** | `java -jar target/relay-1.0.0.jar client alice` |
| **T3 - bob** | `java -jar target/relay-1.0.0.jar client bob` |

> No classpath needed: the shade plugin bundles Jackson and sets `Main-Class`, so the jar
> is self-contained. Prerequisite is Java 21+.

**Docker fallback** — if the machine has no Java. Containerise the *server* only; the
clients still run from the jar and connect to `localhost:9090` as normal:

```bash
docker build -t relay:latest .
docker run -p 9090:9090 relay:latest
```

Full containerisation (server *and* clients) is in [README.md](../README.md), but do not
rehearse it — the jar is the demo, and Docker is insurance you mention rather than perform.

**T1 is the star.** Every registration, acceptance, queue-depth change and disconnect logs
there. Point at it, not at the clients — the interviewer should be watching *state change*.

---

## The nine beats

### 1 — Start the server (T1)

```
INFO   relay starting with effective configuration:
INFO     RELAY_PORT                     = 9090
INFO     RELAY_MAX_FRAME_BYTES          = 65536
INFO     RELAY_MAX_PAYLOAD_BYTES        = 32768
INFO     RELAY_MAX_MAILBOX_MESSAGES     = 1000
INFO     RELAY_MAX_CONNECTIONS          = 256
INFO     RELAY_OUTBOUND_QUEUE_CAPACITY  = 256
INFO     RELAY_SHUTDOWN_TIMEOUT_MS      = 5000
INFO   listening on port 9090
```

> *"Every bound is configurable and printed on boot — mailbox size, message size, connection
> count, queue depth. The brief asks for bounded state and for the service to report its
> limits, so I put them all in one place."*

### 2 — Register both clients (T2, T3)

Both show `<< REGISTERED as ... (pending=0)`.
T1 logs `registered as 'alice' (0 pending, 1 identities known)`.

> **Requirement 1.** *"Two independent identities."*

### 3 — Send (T2)

```
send bob hello
```

T2: `<< ACCEPTED  alice-1` · T3: `<< DELIVER   alice-1 from alice : hello`

> **Requirements 2, 3, 4.** The sentence to say here:
> *"`ACCEPTED` means it's in bob's mailbox — not that bob has it. That's why the brief lists
> confirming the send and acknowledging receipt as two separate requirements."*

### 4 — Acknowledge (T3)

```
ack alice-1
```

T1 logs `bob acked alice-1 (0 remaining)`.

> *"Only now is it removed. Delivered and acknowledged are different states."*

### 5 — Kill bob (T3) — `Ctrl-C`

T1: `session 'bob' retained with 0 message(s) and is now offline`

> *"The identity outlives the connection. The socket is a nullable field on the session, not
> the other way round — that one decision is what makes the next four beats possible."*

### 6 — Send to an offline client (T2)

```
send bob one
send bob two
```

Both `<< ACCEPTED`. T1 logs `bob now holds 1`, then `2`.

> **Requirement 5.** *"Retained while offline, within the mailbox bound."*

### 7 — Bob reconnects (T3)

```
java -jar target/relay-1.0.0.jar client bob
```

```
<< REGISTERED as bob  (pending=2)
<< DELIVER   alice-2 from alice : one     <-- type 'ack alice-2'
<< DELIVER   alice-3 from alice : two     <-- type 'ack alice-3'
```

> **Requirement 6.** *"Same name, same session, backlog delivered — and the count is
> reported before it arrives."*

### 8 — Kill bob again, WITHOUT acking (T3) — `Ctrl-C`

T1: `session 'bob' retained with 2 message(s)`

> *"He received them and never acknowledged them. Watch what happens."*

### 9 — Bob reconnects again (T3)

```
<< REGISTERED as bob  (pending=2)
<< DELIVER   alice-2 from alice : one
<< DELIVER   alice-3 from alice : two
```

> **Requirement 7 — the money shot.**
> *"Both redelivered, in the same order. That works because 'delivered' and 'acknowledged'
> are tracked separately: on disconnect, anything unacknowledged goes back to the head of the
> queue. At-least-once, with redelivery on reconnect."*

---

## Two extras, thirty seconds each

**Rejection leaves the connection usable** (T2):

```
send nobody hi
send bob still-working
```

`<< REJECTED  alice-4  UNKNOWN_RECIPIENT` then `<< ACCEPTED  alice-5`.

> *"A payload or addressing problem is a clean rejection — the frame parsed, so the stream is
> still in sync. A frame-size violation closes the connection instead, because at that point
> I can't locate where the next frame starts."*

**Graceful shutdown** — `Ctrl-C` on T1:

Each client prints `<< SHUTDOWN  server shutting down` then exits.

> *"Stop accepting, tell everyone why, let the writers drain, then close. The shutdown hook
> means `docker stop` gets the same path."*

---

## If something goes wrong

| Symptom | Cause |
|---|---|
| `Connection refused` | Server not started, or wrong port. `RELAY_PORT` defaults to 9090. |
| `no main manifest attribute` | The jar was not built by the shade plugin. Run `./mvnw package`. |
| `REJECTED ... UNKNOWN_RECIPIENT` when bob exists | bob has never registered on *this* server run — sessions are in-memory only |
| `DUPLICATE_MESSAGE_ID` | Shouldn't happen: ids are prefixed per client (`alice-1`, `bob-1`) |
| A client exits immediately | `<< disconnected:` line says why |

Don't debug live. Say what you expected, say what you'd check, and move on to the code —
you have 45 minutes and the tests already prove all of this.

---

## Rehearsal checklist

- [ ] Run all nine beats, start to finish
- [ ] Run them a second time
- [ ] Say the requirement number out loud at beats 2, 3, 5, 6, 7, 9
- [ ] Practise beat 9's sentence — it is the one that matters
