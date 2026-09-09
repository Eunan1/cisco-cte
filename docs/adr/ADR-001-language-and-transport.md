# ADR-001 — Language, runtime, and transport

**Status:** Accepted · **Date:** 2026-09-06

## Context

The brief permits TCP, HTTP, RPC, or gRPC, and any language "you can explain
confidently". It forbids using a messaging library or broker as the relay itself:
registration, mailbox, delivery, retry, and acknowledgement must be our code. It also
says frameworks are acceptable "when you can explain the choice and the relay behaviour
remains in your code".

The submission is defended in a 45-minute session that includes making a live change.

## Decision

**Java 21 (LTS), plain `java.net` TCP sockets, Maven, JUnit 5. No application framework.**

Jackson for JSON, and nothing else at runtime.

## Rationale

**Java** — it is the language on the CV, it is what the other interview slot covers, and
Java 21 gives virtual threads, records, sealed interfaces, and pattern-matching switch,
all of which make this specific problem shorter and clearer to explain.

### Raw TCP

**What TCP is.** TCP is a *transport layer* protocol (OSI layer 4). It sits directly on
IP, which by itself offers only unreliable, unordered, best-effort delivery of individual
packets. TCP adds four guarantees on top:

1. **Connection-oriented** — a three-way handshake establishes a session between two
   endpoints before any data flows, and a `FIN`/`ACK` exchange tears it down.
2. **Reliable** — lost segments are retransmitted. Bytes you write either arrive or the
   connection fails; they are never silently dropped.
3. **Ordered** — bytes arrive in the order they were sent, regardless of the order the
   underlying IP packets took.
4. **Flow-controlled** — the receiver advertises a window, so a fast sender cannot
   overwhelm a slow receiver at the kernel level.

It is also **full-duplex**: one connection carries data in both directions
simultaneously. That matters here, because a client sends `SEND` up the same socket the
server pushes `DELIVER` down. One connection per client is sufficient.

**What TCP deliberately does not give you.** Message boundaries. TCP presents a
*continuous stream of bytes*, not a sequence of messages. If you write 100 bytes then 50
bytes, the peer may read 150 at once, or 3 then 147. That gap is not an oversight — it is
the layer boundary, and filling it is what "designing a protocol" means in this exercise.
See [PROTOCOL.md](../others/PROTOCOL.md) for the framing that fills that gap.

**Why the alternatives lose the material we want to be examined on.** Every alternative
is a protocol built *on top of* TCP that has already made these decisions for us:

| Option | Layer | What it decides for you | What we would lose |
|---|---|---|---|
| **UDP** | Transport | Preserves message boundaries (datagrams), but drops reliability, ordering, and connections entirely | We would have to build retransmission, ordering, and session tracking ourselves — a much larger job than framing, and the brief explicitly says not to build a custom TCP stack |
| **HTTP/1.1** | Application (over TCP) | Framing (headers plus `Content-Length` or chunked encoding), methods, status codes | Framing is HTTP's design, not ours. Worse, HTTP is fundamentally **request/response**: the server cannot spontaneously push. Our `DELIVER` would need SSE, WebSocket, or long-polling bolted on |
| **gRPC** | Application (over HTTP/2) | Binary framing, stream multiplexing, per-stream flow control, and message schemas via protobuf | HTTP/2 already solved framing and backpressure. When asked "how did you frame messages?", the honest answer becomes "HTTP/2 did". The protocol becomes a `.proto` file plus generated stubs |

**What "visibly ours" means concretely.** The brief grades protocol design, shared state,
delivery, acknowledgement, and lifecycle. On raw TCP, every one of those is a decision we
made and can be questioned on:

- *Framing* — we chose the length prefix and can defend it against delimiters (ADR-002).
- *Message boundaries* — we recover them; the bug class exists and we tested for it.
- *Backpressure* — TCP's window protects the kernel buffer, but nothing above it. When a client stops reading, its socket buffer fills and our `write()` would block. The bounded outbound queue and per-connection writer thread are *our* answer to that, and it is what stops one slow client from stalling others.
- *Connection lifecycle* — registration, reattachment on reconnect, and shutdown are ours, not a framework's.

Choosing gRPC would not be wrong engineering; in production it would often be the better
call. It is wrong *for this exercise*, because it moves the graded material into a library.

### Virtual threads — and what Java 17 would have forced us to write

**The problem.** The clearest possible expression of this server is
thread-per-connection: a reader thread parked on `readFully` and a writer thread parked
on `queue.take()`. Straight-line blocking code, no callbacks, no state machines. The
reason that pattern fell out of favour is cost.

**Platform threads.** Before Java 21, every `Thread` was a thin wrapper over an OS thread.
Each reserves a stack (commonly ~1 MB of virtual address space, `-Xss` default), is
scheduled by the kernel, and costs a syscall-level context switch to swap. Our design
uses two threads per connection, so 256 connections is 512 OS threads — workable, but the
pattern does not extend, and blocking a thread means an OS thread sits idle holding its
stack.

**So on Java 17 we would have had two options.**

*Option A — thread-per-connection on platform threads.* Same code we are writing now, but
it stops scaling in the low thousands of connections and wastes an OS thread per blocked
read. Defensible at this exercise's scale; a bad thing to demo as a design.

*Option B — non-blocking NIO with a `Selector`* (the reactor pattern). This is what we
would actually have had to implement, and it is worth knowing concretely:

- Register each `SocketChannel` with a `Selector` for `OP_READ` / `OP_WRITE`, and run an
  event loop on `selector.select()`.
- **Hand-roll partial-read reassembly.** A non-blocking `read()` returns whatever is
  available — possibly three bytes of a four-byte length prefix. Each connection needs a
  persistent `ByteBuffer` accumulating bytes across select cycles plus a small state
  machine (*reading length* → *reading body* → *frame complete*). `readFully` does exactly
  this for us, inside the JDK.
- **Hand-roll partial-write handling.** `write()` may accept fewer bytes than offered when
  the socket buffer is full. So you keep a pending-write buffer per connection, register
  `OP_WRITE` interest when it is non-empty, and *deregister it once drained* — forget the
  deregistration and the selector spins at 100% CPU reporting a permanently writable
  socket.
- **No blocking call may ever occur on the event loop.** A single slow operation stalls
  every connection sharing that loop. All our mailbox work would need careful auditing.
- Business logic inverts into callbacks and explicit state, rather than reading top to
  bottom.

That is roughly two to three hundred lines of subtle, well-known-to-be-buggy
infrastructure that has nothing to do with the relay behaviour the brief actually grades.

**What virtual threads change.** A virtual thread is scheduled by the JVM, not the OS. Its
stack lives on the heap and grows on demand (hundreds of bytes to start, not ~1 MB), and
when it blocks on I/O the JVM **unmounts** it from its carrier platform thread and runs
something else there. The blocking call still blocks *that virtual thread* — the code
reads identically — but it no longer holds an OS thread hostage.

The net effect: **Option A's code with Option B's scalability characteristics.** We keep
`readFully` and `queue.take()`, and the JDK does the multiplexing that we would otherwise
have hand-written.

**The caveat to know.** On Java 21 a virtual thread that blocks inside a `synchronized`
block **pins** its carrier platform thread, defeating the mechanism — enough pinned
carriers and the pool starves. Hence the project convention: `ReentrantLock`, never
`synchronized`, for any lock held across a blocking call. (JEP 491 removed this
limitation in Java 24, but we target 21 LTS, so the convention stands.)

**No Spring** — Spring Boot is the fastest thing for us to write, but every piece of
behaviour it supplies is a piece we cannot claim in the interview, and a small plain-Java
codebase is far easier to navigate live. The relay behaviour must be ours; making the
whole process ours is simpler than drawing that line inside a framework.

## Alternatives considered

**gRPC with a bidirectional stream.** Bidi streaming maps neatly onto push-delivery plus
acks, and framing and flow control come free. Rejected because "design a protocol"
collapses into "write a `.proto`": the framing, bounds, and backpressure discussion —
most of the available marks — disappears into generated code. It also adds a schema
compiler and generated sources to explain.

**HTTP or WebSocket on Spring Boot.** Closest to daily work and quickest to write.
Rejected because HTTP is a poor fit for server-initiated delivery (forcing SSE,
WebSocket, or long-polling), and because registration, connection lifecycle, and
concurrency would largely become framework behaviour rather than ours.

**Go.** Excellent fit — goroutines and channels model this almost too neatly. Rejected
because the adjacent interview is Java-focused and confidence under questioning outweighs
elegance.

## Consequences

- We write framing ourselves, including its bugs. Mitigated by building the codec first, in isolation, with hostile-input tests, before any socket existed.
- We write the write-loop and backpressure ourselves. This is a cost, but it is also the strongest talking point in the submission.
- No dependency injection, no auto-configuration: wiring is explicit constructor calls in `Main`. For a codebase this size that is a readability gain.
- Java 21 is a hard prerequisite (virtual threads, sealed types). Must be stated in the README and pinned in the Dockerfile.
