# STORY-1 — Frames on the wire

| | |
|---|---|
| **Epic** | EPIC-1 — Core relay |
| **Priority** | P0 |
| **Points** | 3 |
| **Status** | Done |
| **Depends on** | — (first story) |

## 1. Goal

### Terms, first

- **Frame** — one self-contained, length-delimited unit of data on the wire. Borrowed from networking, where the same word means the same thing at every layer: Ethernet frames, HTTP/2 frames, WebSocket frames. We say *frame* rather than *message* deliberately, because "message" is already taken in this domain — it is the thing Alice sends Bob. **Every frame carries a frame type; only a `SEND` or `DELIVER` frame carries a message.** `REGISTER`, `ACK`, and `ERROR` are frames that are not messages.
- **Codec** — *co*der/*dec*oder, the same portmanteau as in video codecs (H.264 and friends). Pronounced **"COH-dek"**. A codec is the pair of functions that convert in-memory objects to bytes and back again.
- **Frame codec** — therefore: the one component that knows how a `Frame` object becomes a sequence of bytes, and how a sequence of bytes becomes a `Frame` object again. It is the *only* place in the codebase that thinks in bytes. Everything above it thinks in `Frame` objects.
- **The wire** — jargon for the network link itself. "On the wire" means the serialised byte form actually transmitted, as opposed to the in-memory object form. "Wire format" is the exact byte layout we commit to. Contrast with *in memory* and *at rest* (on disk).

### What exists after this story

A buildable Maven project containing that frame codec — `writeFrame(out, frame)` and
`readFrame(in)` — plus the sealed set of Java types modelling every frame in the protocol,
plus a `RelayConfig` holding the bounds. Every frame type round-trips through a byte
stream in a unit test.

`OutputStream` and `InputStream` here are `java.io`'s abstract byte-stream interfaces, and
the abstraction is the point: **the codec neither knows nor cares where the bytes come
from.** In this story the tests hand it a `ByteArrayOutputStream` and
`ByteArrayInputStream` — pure in-memory buffers, no network, no terminal. In STORY-2 the
server hands it `socket.getInputStream()` and `socket.getOutputStream()` instead, and not
one line of the codec changes.

> **This is not terminal I/O.** The CLI's reading of what you type is a completely
> separate stream (`System.in`) handled in STORY-5: the CLI parses your keystrokes into a
> `Send` or `Ack` *object*, and hands that object to the codec, which writes it to the
> socket. Two different streams, at two different layers.

So yes — this story defines the vocabulary of frames and the rules for turning them into
bytes, and every later story simply calls `codec.writeFrame(...)` / `codec.readFrame(...)`
and never thinks about bytes again.

There are **no sockets, no server, no threads, and no relay behaviour yet**. This story
deliberately isolates the one piece that is pure and easy to test exhaustively, so that
when the server does appear in STORY-2 we are never debugging framing and concurrency at
the same time.

## 2. Why this matters

Framing is the part of "design a protocol" that the brief is really asking about, and it
is the part that most candidates get subtly wrong. Getting it right, in isolation, with
tests, on the first commit means:

- **STORY-2 can trust the wire.** Every bug after this point is a concurrency or
  lifecycle bug, which is where the interesting work is.
- **The frame-size bound lands here**, which is one of the four bounds the brief
  requires — and it lands in the one place where it can be enforced *before* memory is
  allocated.
- **The sealed frame hierarchy created here is the extension point** used in the
  interview. "Add a new operation" becomes **two edits in two files**: add a nested record
  with its `@JsonSubTypes` line beside it, then add a case to the handler switch — and the
  compiler finds the second one for you.

## 3. Concepts & Theory

### 3.1 TCP is a byte stream, not a message stream

This is the sentence to have ready in the interview. A TCP connection guarantees that
bytes arrive in order and without gaps. It guarantees **nothing** about where one
application message ends and the next begins. If you `write()` 100 bytes and then 50
bytes, the peer may `read()` 150 bytes at once, or 3 bytes then 147, or any other split.
Nagle's algorithm, path MTU, and kernel buffering all conspire to make the boundaries
arbitrary and *machine-dependent* — which is why framing bugs pass on localhost and fail
in the interview environment.

**Framing** is the layer you add on top to recover message boundaries. There are exactly
two families:

| Family | How | Cost |
|---|---|---|
| **Delimiter** | A reserved byte marks the end (e.g. `\n` for NDJSON, `\r\n\r\n` for HTTP headers) | You must escape or forbid the delimiter inside the payload. You cannot know the message size until you have read it all, so bounding requires counting as you go. Human-readable — you can drive it with `netcat`. |
| **Length prefix** | A fixed-width integer states how many bytes follow | No escaping problem at all — the payload is opaque. The size is known *before* you read the body, so bounds are checkable pre-allocation. Not readable with `netcat`. |

We take **length prefix**. The decisive reason is the bound: the brief requires message
sizes to be bounded and requires the service to report resource limits. With a length
prefix, "is this frame too big?" is answered by an `if` on an integer we have already
read, before a single byte of body is allocated. With a delimiter we would have to read
and count, meaning a hostile client can make us do work proportional to the garbage it
sends.

This trade-off — *debuggability versus pre-allocation bounds checking* — is the single
best thing to volunteer when they ask about protocol design.

### 3.2 Why four bytes, big-endian

#### Format capability is not policy limit

These are two different things and it is worth keeping them apart, because they get
conflated constantly:

| | What it is | Determined by | Value |
|---|---|---|---|
| **Format capability** | The largest frame size the encoding is *able to express* | The width of the length prefix | 4 bytes signed = `Integer.MAX_VALUE` = 2,147,483,647 bytes (about 2 GiB) |
| **Policy limit** | The largest frame we *permit* | An `if` in our code | `RelayConfig.maxFrameBytes`, default 64 KiB, configurable |

The 2 GiB figure is **not storage, not a message count, and not anything we ever
allocate**. It is simply the ceiling the number format could describe — and we then choose
to operate far below it. The 64 KiB figure is the one that actually constrains anything,
and it lives in configuration rather than in the wire format.

#### Why four bytes rather than two

Two bytes would give an unsigned maximum of 65,535 — which covers a 64 KiB limit with
essentially zero headroom. More importantly it would **weld the wire format to today's
policy**: raising the limit to 1 MiB later would become a breaking protocol change,
because every existing peer would be parsing a 2-byte prefix. With four bytes the limit is
a config value we can move freely, and old and new peers still agree on how to find the
frame boundary.

Four bytes also maps exactly onto `DataOutputStream.writeInt` / `DataInputStream.readInt`,
so no manual byte assembly is needed.

#### Why 64 KiB specifically

It is a round number chosen for headroom, and saying so plainly is a better interview
answer than inventing a derivation. What matters is that the bound **exists**, is
**enforced before allocation** (§3.4), and is **configurable**. The sanity check: 256
connections at a 64 KiB worst-case read buffer is roughly 16 MiB, which is comfortably
safe.

#### Why big-endian

Big-endian is **network byte order** — the convention every network protocol uses for
multi-byte integers, so a non-Java client written against our spec finds nothing
surprising. Conveniently, `writeInt` and `readInt` are *specified* to be big-endian, so we
get the convention for free rather than having to reach for `ByteBuffer.order(...)`.

#### How this relates to the brief's bounds

The brief requires that *"mailboxes, message sizes, active connections, and buffers are
bounded"*. It never uses the word "frame", so the mapping is worth stating:

| Our bound | Applies to | Breach response | Why that response |
|---|---|---|---|
| `maxPayloadBytes` (32 KiB) | The user's content inside a `SEND` | `REJECTED` / `PAYLOAD_TOO_LARGE`, **connection survives** | The frame parsed correctly; we simply decline its contents. Nothing about the stream is in doubt. |
| `maxFrameBytes` (64 KiB) | The whole encoded frame: 4-byte prefix + JSON body | `FRAME_TOO_LARGE`, **connection closed** | We can no longer locate where the next frame begins, so the byte stream is unusable from here on. |

`maxPayloadBytes` is the direct reading of "message sizes are bounded". `maxFrameBytes`
sits upstream of it and is what makes that bound true against a *hostile* client rather
than merely a well-behaved one: without it, a client sends four bytes claiming
`Integer.MAX_VALUE` and exhausts the heap before we ever reach a payload check.

Note also that frame size and message size are related but not equal:

```
frame bytes = 4 (length prefix) + JSON body bytes
JSON body   = envelope fields (type, messageId, to, from) + JSON-escaped payload
```

which is why `maxPayloadBytes` must sit below `maxFrameBytes` with room to spare — an
invariant enforced in `RelayConfig`'s compact constructor (§6.2).

### 3.3 Short reads: the classic framing bug

`InputStream.read(byte[] b)` returns **the number of bytes actually read**, which may be
anything from 1 to `b.length`. It is under no obligation to fill the array. Code like:

```java
byte[] body = new byte[len];
in.read(body);            // BUG: may fill only part of the array
```

works every single time on localhost with small messages, and corrupts data the moment a
message is split across TCP segments. The fix is `DataInputStream.readFully(byte[])`,
which loops until the array is full or the stream ends. Use it, and know why.

### 3.4 Bound before allocate

Having read the length prefix we hold an `int` that came from the network and is
therefore hostile until proven otherwise. Two failure modes:

- **Negative.** `new byte[-1]` throws `NegativeArraySizeException` — an unchecked
  exception from deep inside your read loop, which is a confusing crash rather than a
  clean protocol error.
- **Absurdly large.** `new byte[2_000_000_000]` is an immediate `OutOfMemoryError`. A
  single 4-byte write from a malicious client kills the server for everybody.

So the check goes **between** reading the int and allocating the array. This is a
three-line detail that demonstrates you think about untrusted input, and it is worth
pointing at during the code tour.

### 3.5 Sealed interfaces plus records: the extension point

Java 21 lets us model the protocol as a **sealed interface** implemented by **records**:

```java
public sealed interface Frame {
    record Register(String clientId) implements Frame { }
    record Ack(String messageId)     implements Frame { }
    // ...
}
```

`sealed` means the compiler knows the complete set of implementations. That makes a
`switch` over `Frame` **exhaustive without a `default` branch** — and if you add a new
frame type, every switch that does not handle it becomes a **compile error**, not a
runtime surprise.

#### Why the records are nested, and why there is no `permits` clause

A sealed type normally needs a `permits` clause listing its implementations. Java makes
one exception: **if every permitted subtype is declared in the same source file, `permits`
is inferred.** Nesting the records directly inside the interface satisfies that, so the
clause disappears entirely.

That is worth doing for three reasons:

1. **One list, not two.** A `permits` clause is a second list that must stay in sync with the first. Forgetting to update it is a pure-maintenance compile error that teaches nobody anything.
2. **Reading is better.** `Frame.Register` says "a Frame of kind Register". A separate `Frames` holder class would say "a thing from the Frames bag" — and a plural-named class holding nested types is the `Utils`/`Constants` smell in a better outfit. It is also *not* the `Collections`/`Objects`/`Arrays` idiom, which holds static **methods**, not types.
3. **`Frame.Error` stops shadowing `java.lang.Error`** in the reader's head, because the qualifier is always present.

Nested types in an interface are implicitly `public static`, so `record X(...) implements
Frame { }` is the whole declaration.

The extension story gets shorter as a result: adding an operation is a record plus its
`@JsonSubTypes` line — adjacent, in one file — and then a switch case. **Two edits, and
the compiler tells you the second one.** That is the thing to demonstrate when they ask
for a small change; it beats "I search for the places" comfortably.

Records give us the rest for free: immutability (safe to hand between threads without
any synchronisation), a canonical constructor for validation, and `equals`/`hashCode`
so round-trip tests are a one-line assertion.

### 3.6 Jackson polymorphic typing

Jackson needs to know which record to instantiate from a JSON object. `@JsonTypeInfo`
with `Id.NAME` and `As.PROPERTY` writes a discriminator field into the JSON:

```json
{"type":"SEND","messageId":"m1","to":"bob","payload":"hi"}
```

`@JsonSubTypes` maps each name to a class. Two settings matter for a wire protocol:

- `FAIL_ON_UNKNOWN_PROPERTIES` **disabled** — so a newer client sending an extra field
  does not break an older server. Forward compatibility for free, and a good thing to
  mention.
- Deserialisation of an unknown `type` value must produce a clean `MALFORMED_FRAME`
  error, not a stack trace escaping into the read loop.

Jackson constructs records via their canonical constructor with `-parameters` enabled at
compile time, or via `@JsonProperty` annotations. We set `-parameters` in the compiler
plugin so the records stay clean.

## 4. Design & Approach

**Chosen:** 4-byte big-endian length prefix, then a UTF-8 JSON body, decoded into a
sealed `Frame` hierarchy of records via Jackson.

Alternatives considered:

| Option | Rejected because |
|---|---|
| Newline-delimited JSON | Readable with `netcat`, which is genuinely nice for a demo, but bounds checking becomes read-and-count, and a client that never sends a newline makes us buffer indefinitely. The pre-allocation bound is worth more than the readability. |
| Java serialisation | Well-known deserialisation attack surface, JVM-only, opaque on the wire. Not defensible. |
| Custom binary encoding | Compact and fast, but neither matters at this scale, and it costs time we owe to concurrency and lifecycle. Also much harder for an interviewer to read. |
| Protobuf | Would mean adopting a schema compiler and generated code, and the brief wants the protocol design to be visibly ours. Also drifts toward the gRPC option we already rejected in ADR-001. |

Relevant ADRs: [ADR-001](../adr/ADR-001-language-and-transport.md),
[ADR-002](../adr/ADR-002-framing-and-serialisation.md).

## 5. Files to create / modify

| Path | Purpose |
|---|---|
| `pom.xml` | Java 21, Jackson, JUnit 5, surefire, shade plugin, `-parameters` |
| `src/main/java/com/eunangavin/relay/config/RelayConfig.java` | All bounds and the port, with environment-variable overrides |
| `src/main/java/com/eunangavin/relay/protocol/Frame.java` | Sealed interface with all ten frame records nested inside it — the whole protocol on one screen, no `permits` clause needed |
| `src/main/java/com/eunangavin/relay/protocol/ErrorCode.java` | Enum of the documented error codes |
| `src/main/java/com/eunangavin/relay/protocol/FrameCodec.java` | `writeFrame` / `readFrame` |
| `src/main/java/com/eunangavin/relay/protocol/ProtocolException.java` | Checked exception carrying an `ErrorCode` |
| `src/test/java/com/eunangavin/relay/protocol/FrameCodecTest.java` | Round-trip and hostile-input tests |
| `src/test/java/com/eunangavin/relay/config/RelayConfigTest.java` | Defaults and override parsing |
| `.gitignore` | Already present |

## 6. Implementation

### 6.1 `pom.xml` essentials

```xml
<properties>
  <maven.compiler.release>21</maven.compiler.release>
  <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>
</properties>

<build>
  <plugins>
    <plugin>
      <groupId>org.apache.maven.plugins</groupId>
      <artifactId>maven-compiler-plugin</artifactId>
      <configuration>
        <!-- Jackson binds record components by name; without this they are arg0, arg1... -->
        <compilerArgs><arg>-parameters</arg></compilerArgs>
      </configuration>
    </plugin>
  </plugins>
</build>
```

Dependencies: `com.fasterxml.jackson.core:jackson-databind` and
`org.junit.jupiter:junit-jupiter` (test scope). Nothing else yet — the shade plugin and
a `Main-Class` arrive in STORY-6 when there is something to run.

### 6.2 `RelayConfig`

```java
public record RelayConfig(
        int port,
        int maxFrameBytes,
        int maxPayloadBytes,
        int maxMailboxMessages,
        int maxConnections,
        int outboundQueueCapacity,
        Duration shutdownTimeout) {

    public static RelayConfig defaults() {
        return new RelayConfig(9090, 64 * 1024, 32 * 1024, 1000, 256, 256,
                Duration.ofSeconds(5));
    }

    /** Every bound is overridable so the README can document how limits are controlled. */
    public static RelayConfig fromEnvironment() {
        RelayConfig d = defaults();
        return new RelayConfig(
                intEnv("RELAY_PORT", d.port()),
                intEnv("RELAY_MAX_FRAME_BYTES", d.maxFrameBytes()),
                intEnv("RELAY_MAX_PAYLOAD_BYTES", d.maxPayloadBytes()),
                intEnv("RELAY_MAX_MAILBOX_MESSAGES", d.maxMailboxMessages()),
                intEnv("RELAY_MAX_CONNECTIONS", d.maxConnections()),
                intEnv("RELAY_OUTBOUND_QUEUE_CAPACITY", d.outboundQueueCapacity()),
                Duration.ofMillis(intEnv("RELAY_SHUTDOWN_TIMEOUT_MS",
                        (int) d.shutdownTimeout().toMillis())));
    }

    public RelayConfig {
        if (maxPayloadBytes >= maxFrameBytes) {
            throw new IllegalArgumentException(
                    "maxPayloadBytes must be below maxFrameBytes to leave room for envelope fields");
        }
        // ...remaining positivity checks
    }
}
```

The compact-constructor invariant is worth keeping: the JSON envelope adds overhead
around the payload, so a payload exactly at the frame limit could never be sent. Catching
that at construction is better than at 3 a.m.

### 6.3 The frame hierarchy

```java
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "type")
@JsonSubTypes({
        @JsonSubTypes.Type(value = Frame.Register.class,   name = "REGISTER"),
        @JsonSubTypes.Type(value = Frame.Registered.class, name = "REGISTERED"),
        @JsonSubTypes.Type(value = Frame.Send.class,       name = "SEND"),
        @JsonSubTypes.Type(value = Frame.Accepted.class,   name = "ACCEPTED"),
        @JsonSubTypes.Type(value = Frame.Rejected.class,   name = "REJECTED"),
        @JsonSubTypes.Type(value = Frame.Deliver.class,    name = "DELIVER"),
        @JsonSubTypes.Type(value = Frame.Ack.class,        name = "ACK"),
        @JsonSubTypes.Type(value = Frame.AckOk.class,      name = "ACK_OK"),
        @JsonSubTypes.Type(value = Frame.Error.class,      name = "ERROR"),
        @JsonSubTypes.Type(value = Frame.Shutdown.class,   name = "SHUTDOWN")
})
public sealed interface Frame {          // no permits clause - inferred from this file

    // client -> server
    record Register(String clientId)                                implements Frame { }
    record Send(String messageId, String to, String payload)        implements Frame { }
    record Ack(String messageId)                                    implements Frame { }

    // server -> client, in response
    record Registered(String clientId, int pending)                 implements Frame { }
    record Accepted(String messageId)                               implements Frame { }
    record Rejected(String messageId, ErrorCode code, String reason) implements Frame { }
    record AckOk(String messageId)                                  implements Frame { }
    record Error(ErrorCode code, String reason)                     implements Frame { }

    // server -> client, unsolicited
    record Deliver(String messageId, String from, String payload)   implements Frame { }
    record Shutdown(String reason)                                  implements Frame { }
}
```

Ten frame types, three of which are client-initiated operations. Keep it there.

Nested types in an interface are implicitly `public static`, so no modifiers are needed on
the records. Note that each `@JsonSubTypes` entry sits directly above the record it names
— that adjacency is what makes adding an operation a single coherent edit.

### 6.4 `FrameCodec`

```java
public final class FrameCodec {

    private static final ObjectMapper MAPPER = JsonMapper.builder()
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES) // forward compatible
            .build();

    private final int maxFrameBytes;

    public FrameCodec(int maxFrameBytes) { this.maxFrameBytes = maxFrameBytes; }

    public void writeFrame(DataOutputStream out, Frame frame) throws IOException, ProtocolException {
        byte[] body = MAPPER.writeValueAsBytes(frame);
        if (body.length > maxFrameBytes) {
            throw new ProtocolException(ErrorCode.FRAME_TOO_LARGE,
                    "encoded frame " + body.length + " exceeds limit " + maxFrameBytes);
        }
        out.writeInt(body.length);   // big-endian by specification
        out.write(body);
        out.flush();                 // without this the frame can sit in the buffer forever
    }

    /** @return the next frame, or null on a clean end of stream (peer closed between frames). */
    public Frame readFrame(DataInputStream in) throws IOException, ProtocolException {
        int length;
        try {
            length = in.readInt();
        } catch (EOFException eof) {
            return null;             // clean close, not an error
        }

        // Bound BEFORE allocating: this int came from the network and is not trusted.
        if (length < 0 || length > maxFrameBytes) {
            throw new ProtocolException(ErrorCode.FRAME_TOO_LARGE,
                    "declared frame length " + length + " outside [0, " + maxFrameBytes + "]");
        }

        byte[] body = new byte[length];
        in.readFully(body);          // NOT in.read(body) - see section 3.3

        try {
            return MAPPER.readValue(body, Frame.class);
        } catch (JacksonException e) {
            throw new ProtocolException(ErrorCode.MALFORMED_FRAME, e.getOriginalMessage());
        }
    }
}
```

Three things to point at during the code tour: the `EOFException` distinguishing a clean
close from a truncated frame, the bound sitting above the allocation, and `readFully`.

Note the codec never touches `StandardCharsets` directly because Jackson's byte-array
methods are UTF-8 by specification. If you ever hand-build a string here, pass the
charset explicitly.

### 6.5 Test skeleton

```java
class FrameCodecTest {

    private final FrameCodec codec = new FrameCodec(1024);

    private Frame roundTrip(Frame original) throws Exception {
        var bytes = new ByteArrayOutputStream();
        codec.writeFrame(new DataOutputStream(bytes), original);
        return codec.readFrame(new DataInputStream(new ByteArrayInputStream(bytes.toByteArray())));
    }

    @ParameterizedTest
    @MethodSource("everyFrameType")
    void roundTripsEveryFrameType(Frame original) throws Exception {
        assertEquals(original, roundTrip(original));   // records give us equals() free
    }

    @Test
    void rejectsDeclaredLengthAboveLimitWithoutAllocating() {
        var hostile = new DataInputStream(new ByteArrayInputStream(
                ByteBuffer.allocate(4).putInt(Integer.MAX_VALUE).array()));
        var ex = assertThrows(ProtocolException.class, () -> codec.readFrame(hostile));
        assertEquals(ErrorCode.FRAME_TOO_LARGE, ex.code());
    }

    @Test
    void rejectsNegativeDeclaredLength() { /* putInt(-1), expect FRAME_TOO_LARGE */ }

    @Test
    void returnsNullOnCleanEndOfStream() throws Exception {
        assertNull(codec.readFrame(new DataInputStream(new ByteArrayInputStream(new byte[0]))));
    }

    @Test
    void throwsOnTruncatedBody() { /* length says 50, supply 10 bytes, expect EOFException */ }

    @Test
    void rejectsUnknownFrameType() { /* {"type":"NONSENSE"} -> MALFORMED_FRAME */ }

    @Test
    void tolerantOfUnknownFields() { /* SEND plus an extra field still parses */ }

    @Test
    void readsFrameSplitAcrossReads() {
        // Feed a stream that returns 1 byte per read() call; readFully must still work.
        // This is the test that would have caught the in.read(body) bug.
    }
}
```

That last test is the one worth writing carefully — wrap the `ByteArrayInputStream` in a
decorator whose `read(byte[], int, int)` always returns at most one byte. It reproduces
the TCP-segmentation bug deterministically, on one thread, with no sockets. It is also a
great thing to show an interviewer.

## 7. Gotchas & pitfalls

1. **`in.read(body)` instead of `in.readFully(body)`** — passes locally, corrupts under real segmentation. Covered by the split-read test above.
2. **Bounds check after allocation** — negative length throws `NegativeArraySizeException`, huge length throws `OutOfMemoryError`. Both must be impossible.
3. **Missing `-parameters`** — Jackson binds record components to `arg0`, `arg1`… and every round-trip test fails with a baffling message. Set it in the compiler plugin now.
4. **Forgetting `out.flush()`** — `DataOutputStream` over a `BufferedOutputStream` will hold the frame. In STORY-2 this becomes "my client hangs forever" and costs an hour.
5. **`EOFException` on the length prefix is normal.** A peer closing between frames is a clean disconnect. Only a `EOFException` *inside* the body is a truncation error. Distinguish them or you will log clean shutdowns as failures.
6. **`permits` is only inferred while every record stays in `Frame.java`.** The moment you move one out to its own file, the interface stops compiling until you add an explicit `permits` clause naming all ten. That is the trade for the convenience — worth knowing before you "tidy up" the file.
7. **Do not put `correlationId` in yet.** It is tempting. Responses correlate naturally by `messageId` and `clientId` today. Adding it is a fine live edit if they ask for one.
8. **Payload as `String`, not `byte[]`.** Binary payloads would need base64 in JSON, which inflates by 33% and complicates the payload bound. Document the choice; it is a legitimate limitation.
9. **`maxPayloadBytes` must sit below `maxFrameBytes`** with room for the envelope, or a maximal payload can never be encoded. Enforced in the compact constructor.

## 8. Acceptance Criteria

- [ ] `mvn clean verify` succeeds from a clean clone on Java 21.
- [ ] Every one of the ten frame types round-trips through a byte stream and compares equal.
- [ ] A declared length above `maxFrameBytes` produces `ProtocolException(FRAME_TOO_LARGE)` with no allocation of that size.
- [ ] A negative declared length produces `ProtocolException(FRAME_TOO_LARGE)`, never `NegativeArraySizeException`.
- [ ] An empty stream returns `null` (clean close) rather than throwing.
- [ ] A truncated body throws rather than returning a half-populated frame.
- [ ] An unknown `type` discriminator produces `ProtocolException(MALFORMED_FRAME)`.
- [ ] An unknown extra JSON field is ignored and the frame still parses.
- [ ] A frame delivered one byte per `read()` call decodes correctly.
- [ ] `RelayConfig.fromEnvironment()` honours every documented environment variable and falls back to the default when unset or unparseable.
- [ ] `RelayConfig` rejects a construction where `maxPayloadBytes >= maxFrameBytes`.

## 9. Tests to write

| Test | Asserts |
|---|---|
| `roundTripsEveryFrameType` (parameterised over all 10) | Encode then decode yields an equal record |
| `rejectsDeclaredLengthAboveLimitWithoutAllocating` | `FRAME_TOO_LARGE` on `Integer.MAX_VALUE` prefix |
| `rejectsNegativeDeclaredLength` | `FRAME_TOO_LARGE`, not `NegativeArraySizeException` |
| `returnsNullOnCleanEndOfStream` | Clean close is distinguishable from an error |
| `throwsOnTruncatedBody` | Partial frame never yields a `Frame` |
| `rejectsUnknownFrameType` | `MALFORMED_FRAME` |
| `tolerantOfUnknownFields` | Forward compatibility holds |
| `readsFrameSplitAcrossReads` | `readFully` semantics; the segmentation-bug regression test |
| `writeRejectsOversizeFrame` | The bound is enforced on the write side too |
| `RelayConfigTest.defaultsAndOverrides` | Env parsing, fallback on garbage input |
| `RelayConfigTest.rejectsPayloadLimitAboveFrameLimit` | Compact-constructor invariant |

No Testcontainers or database in this story — there is no I/O beyond in-memory streams,
which is exactly why this story exists as its own unit.

## 10. Manual verification

```bash
mvn clean verify
```

Expected: `BUILD SUCCESS`, with the surefire summary showing the codec and config tests
run and zero failures.

```bash
mvn -q test -Dtest=FrameCodecTest
```

Expected: all `FrameCodecTest` cases pass.

To eyeball the wire format, add a temporary `main` that encodes one `Send` to a file and
hex-dump it:

```bash
xxd /tmp/frame.bin | head -3
```

Expected: four bytes of big-endian length, then readable JSON beginning `{"type":"SEND"`.
Delete the temporary `main` afterwards.

## 11. Out of scope

| Not in this story | Owned by |
|---|---|
| Sockets, accept loop, threads, connection lifecycle | STORY-2 |
| Registration, sessions, the registry | STORY-2 |
| Mailboxes, sending, delivery | STORY-3 |
| Acknowledgement, inflight, redelivery | STORY-3 |
| The `maxPayloadBytes` bound being *enforced* on a `SEND` (it is only defined here) | STORY-3 |
| Shade plugin, `Main-Class`, runnable jar | STORY-6 |
| A `correlationId` field in the envelope | Deliberately deferred; a candidate live edit |

## 12. Definition of Done

- [ ] Compiles (`mvn clean compile`)
- [ ] All tests green (`mvn test`)
- [ ] Every acceptance criterion met
- [ ] Manual verification passes
- [ ] ADR-002 written up to match what was actually built
- [ ] PR opened summarising what / files / deviations / verification

---

## 13. Walkthrough essentials

Everything this story put into the codebase, and what each thing is for. If you can say
these sentences unprompted, the protocol half of the interview is covered.

### What was introduced

| Thing | What it is | Why it exists |
|---|---|---|
| **Frame** | One length-delimited unit on the wire | TCP has no message boundaries; a frame is the unit we recover |
| **Message** | The content Alice sends Bob | Deliberately a *narrower* word than frame — only `SEND` and `DELIVER` carry one |
| **`Frame.java`** | Sealed interface, ten records nested inside | The whole protocol on one screen; sealed makes handler switches exhaustive at compile time |
| **`FrameCodec.java`** | `writeFrame` / `readFrame` | The only class in the codebase that thinks in bytes |
| **`ErrorCode.java`** | 11 codes, grouped by blast radius | The brief demands defined reporting of invalid input and resource limits |
| **`ProtocolException.java`** | Checked, carries an `ErrorCode` | Forces the caller to choose: reply with ERROR, or close |
| **`RelayConfig.java`** | Six bounds + env overrides | One place for every limit; the README documents exactly this |

### The five sentences that carry the most weight

1. **"TCP is a byte stream, not a message stream."** It guarantees my bytes arrive in order; it says nothing about where my messages start and stop, because it splits on network boundaries — MTU, congestion window, Nagle — not application ones.
2. **"I chose a length prefix over a delimiter because the bound becomes checkable before allocation."** With NDJSON I'd have to read and count, so a client that never sends a delimiter makes me do work proportional to its garbage.
3. **"The bound sits above the allocation, not below it."** That int came from the network. Negative gives `NegativeArraySizeException`; enormous gives `OutOfMemoryError` — one 4-byte write killing the server for everyone.
4. **"`readFully`, not `read`."** `read(byte[])` returns whatever is available and needn't fill the array. That bug passes every localhost test and corrupts the moment a frame spans segments.
5. **"Format capability is not policy limit."** Four bytes can express ~2 GiB; we permit 64 KiB. The width is the wire format, the limit is config — so the limit can move without a breaking protocol change.

### Two things to physically point at

- **`FrameCodec.readFrame`** — the `EOFException` distinguishing clean close from truncation, the bound above `new byte[length]`, and `readFully`. Three lines, three decisions.
- **`FrameCodecTest.readsFrameSplitAcrossReads`** — a decorator returning one byte per read, reproducing TCP segmentation deterministically with no sockets. It is the regression test for the bug in point 4.

## 14. Questions to be able to answer

Ordered by how likely they are to be asked and how much a weak answer costs.

| # | Question | Answer anchor |
|---|---|---|
| 1 | Why did you need framing at all? | TCP orders *bytes*, not messages. It doesn't know my messages exist. |
| 2 | Why length prefix rather than a delimiter? | Bound checkable pre-allocation; no escaping problem. Cost: can't drive it with `netcat`. |
| 3 | Walk me through `readFrame`. | readInt → EOF means clean close → bound check → allocate → readFully → Jackson → MALFORMED_FRAME on parse failure. |
| 4 | What if a client lies about the length? | Negative or over-limit both give `FRAME_TOO_LARGE` *before* allocation. Tested with `Integer.MAX_VALUE` and `-1`. |
| 5 | Why `readFully` and not `read`? | `read` may return one byte. The split-read test proves it. |
| 6 | Why four bytes when your limit is 64 KiB? | Two bytes maxes at 65,535 — no headroom, and it welds the format to today's policy. |
| 7 | What is big-endian and why does it matter? | Network byte order, MSB first. Read `00 00 00 3A` as little-endian and you get 973,078,528. `writeInt`/`readInt` are specified big-endian. |
| 8 | Does the length include the prefix? | No. Both ends must agree — mixing conventions desyncs the stream permanently. |
| 9 | Why sealed interface plus records? | Exhaustive switch with no `default`; adding a frame type becomes a compile error. Records give immutability (frames cross threads) and `equals` (one-line round-trip test). |
| 10 | Show me how you'd add a new operation. | Add the nested record with its `@JsonSubTypes` line, compile, let the error name the switch. Two edits. |
| 11 | Why are there two size limits with different responses? | Payload breach = clean `REJECTED`, connection survives; frame breach = close, because the stream is no longer locatable. |
| 12 | Why is `ProtocolException` checked, and not an `IOException`? | Checked forces a decision at the call site. Separate from `IOException` because "socket broke" and "peer sent nonsense" need opposite handling. |
| 13 | Why is `FAIL_ON_UNKNOWN_PROPERTIES` disabled? | Forward compatibility: a newer client adding a field doesn't break an older server. One line. |
| 14 | How do you test framing without a network? | In-memory streams, plus a decorator that dribbles one byte per read. Deterministic, single-threaded, no sockets. |
| 15 | What don't your tests cover? | Real network segmentation, multi-JVM, load. Deliberate: I tested the state machine and the hostile inputs, and left environmental behaviour to a level of infrastructure this exercise doesn't have. |
| 16 | Why JSON rather than a binary encoding? | Readable in a hex dump and in logs; tolerant of added fields; no schema tooling. Efficiency is irrelevant at this scale. |
| 17 | Why not protobuf or gRPC? | It would move the framing, bounds and backpressure decisions into a library — exactly the material the exercise is grading. |
| 18 | What's the `-parameters` flag for? | Jackson binds record components by name; without it they compile to `arg0`, `arg1` and every round-trip fails. |
| 19 | Where does `maxPayloadBytes` get enforced? | Not here — it's only *defined* in STORY-1. Enforcement lands on `SEND` in STORY-3. |
| 20 | Why is `maxPayloadBytes` forced below `maxFrameBytes`? | The JSON envelope wraps the payload, so a payload at the frame limit could never encode. Caught in the compact constructor. |
