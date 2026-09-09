# ADR-002 — Framing and serialisation

**Status:** Accepted · **Date:** 2026-09-06

## Context

TCP delivers an ordered stream of bytes with no message boundaries. The application must
supply framing. The brief separately requires that message sizes are bounded and that the
service reports invalid input and resource limits — both of which interact directly with
the framing choice.

## Decision

**4-byte big-endian length prefix, followed by a UTF-8 JSON body**, decoded into a sealed
`Frame` hierarchy of Java records using Jackson polymorphic typing on a `type`
discriminator field.

```
+--------------------+--------------------------------+
| 4-byte length (BE) | UTF-8 JSON body (length bytes) |
+--------------------+--------------------------------+
```

## Rationale

**Length prefix over delimiter.** The deciding factor is where the size bound can be
enforced. With a length prefix the frame size is known from four bytes, so the limit is
checked *before* any body buffer is allocated — a hostile client cannot make the server
allocate or scan anything by lying about size. With a delimiter (newline-delimited JSON)
we would have to read and count until either the delimiter or the limit arrives, letting
a client that never sends a delimiter drive work proportional to its garbage. Length
prefixing also removes the delimiter-escaping problem entirely: the body is opaque bytes.

The cost is real and worth stating: NDJSON can be driven from `netcat` or `telnet`, and
length-prefixed frames cannot. We accept losing that for the bounds property, and
mitigate it with a small CLI client.

**Four bytes, big-endian.** Maps exactly onto `DataOutputStream.writeInt` /
`DataInputStream.readInt`, which are specified big-endian — network byte order, so a
non-Java client would find nothing surprising. Two bytes would cover the current 64 KiB
limit but would couple the wire format to that bound.

**JSON over binary.** Readable in a hex dump and in logs, tolerant of added fields, and
zero schema tooling. Encoding efficiency is irrelevant at this scale, and the time saved
belongs to concurrency and lifecycle work.

**Sealed interface + records.** A sealed `Frame` makes handler `switch` statements
exhaustive at compile time, so adding a frame type turns every unhandled site into a
compile error rather than a runtime surprise. Records give immutability — frames cross
thread boundaries constantly and immutability means they need no synchronisation —
plus `equals` for one-line round-trip assertions.

## Consequences

- `FAIL_ON_UNKNOWN_PROPERTIES` is disabled, so a newer client sending extra fields does not break an older server. Cheap forward compatibility.
- Payloads are `String`, not `byte[]`. Binary payloads would need base64, inflating size by a third and muddying the payload bound. This is a documented limitation.
- Records require `-parameters` at compile time for Jackson to bind components by name. Set in the compiler plugin; forgetting it produces confusing `arg0`/`arg1` failures.
- Two distinct size limits exist and behave differently: exceeding `maxFrameBytes` kills the connection (the byte stream is no longer trustworthy), while exceeding `maxPayloadBytes` is a clean `REJECTED` (the frame parsed fine). `maxPayloadBytes` must stay below `maxFrameBytes` with room for envelope fields.
- A clean close between frames surfaces as `EOFException` on the length read and must be treated as a normal disconnect, not an error.

## Alternatives considered

| Option | Verdict |
|---|---|
| Newline-delimited JSON | Rejected — bounds become read-and-count; escaping burden; but genuinely better for live demos |
| Java serialisation | Rejected — deserialisation attack surface, JVM-only, opaque |
| Custom binary encoding | Rejected — no benefit at this scale, costs time owed to concurrency, harder for a reviewer to read |
| Protobuf | Rejected — schema compiler and generated code obscure the protocol we are meant to have designed |

## Deferred

A `correlationId` on the envelope. Responses correlate naturally today (`SEND`/`ACCEPTED`
by `messageId`, `REGISTER`/`REGISTERED` by `clientId`), so it would be unused weight. It
becomes necessary the moment a client can have two operations of the same kind in flight.
Noted in `APPROACH.md` as a next step — and it is a clean candidate for a live edit.
