# Wire Protocol

> The framing layer, with the problem it solves and where each decision lives in code.
> Line references are to [`FrameCodec.java`](../src/main/java/com/eunangavin/relay/protocol/FrameCodec.java).

---

## 1. The problem: TCP has no message boundaries

TCP already does the ordering. What it does not do is tell you where *your* messages
start and stop.

| TCP guarantees | TCP does not |
|---|---|
| Every byte arrives (lost segments are retransmitted) | Preserve **your** message boundaries |
| Bytes arrive in the order sent | Know that your messages exist at all |
| No duplicates, no gaps | |
| Receiver cannot be flooded (flow control) | |

TCP orders **bytes**, not messages. It chops the stream into its own segments sized by
MTU, congestion window, and Nagle's algorithm — none of which have anything to do with
where a frame begins.

### The partition problem

```
YOU SEND                                        YOU RECEIVE
────────                                        ───────────
writeFrame(A)   20 bytes                        read() →  12 bytes
writeFrame(B)   15 bytes                        read() →  40 bytes
writeFrame(C)   30 bytes                        read() →  13 bytes
────────────────────────                        ───────────────────
     65 bytes total                                  65 bytes total ✓


On the wire — one continuous stream, no separators anywhere:

 ┌────────── A (20) ─────────┬────── B (15) ─────┬────────── C (30) ──────────┐
 │                           │                   │                            │
 └───────────────────────────┴───────────────────┴────────────────────────────┘
 ▲                                                                            ▲
 byte 0                                                                 byte 64

What your read() calls actually hand you — the splits land wherever the
network decided, and nothing lines up with a frame boundary:

 ┌──── read#1 (12) ────┬─────────── read#2 (40) ───────────┬─── read#3 (13) ───┐
 └─────────────────────┴───────────────────────────────────┴───────────────────┘
```

`read#2` contains the tail of A, all of B, and the head of C, glued together with no
marker showing where the joins are.

**The trap:** on localhost with small messages each `read()` usually happens to return
exactly one frame, so buggy code passes every test. It breaks on a real network — or in
the interview environment.

---

## 2. The fix: declare the length first

```
WITHOUT framing — unreadable:

  {"type":"SEND",...}{"type":"ACK",...}
                     ▲
                     where does the first end? You would have to parse
                     JSON character by character to find out.

WITH a 4-byte length prefix:

  ┌──────────────┬────────────────────┬──────────────┬───────────────────┐
  │ 00 00 00 3A  │ {"type":"SEND",…}  │ 00 00 00 1F  │ {"type":"ACK",…}  │
  └──────────────┴────────────────────┴──────────────┴───────────────────┘
    read 4 →58      then read 58         read 4 →31      then read 31
```

The reader never guesses how much to ask for.

### A real frame

`new Frame.Ack("m1")` on the wire — 35 bytes total:

```
  00 00 00 1F   7B 22 74 79 70 65 22 3A 22 41 43 4B 22 2C ...
  └─ prefix ─┘  │  {  "  t  y  p  e  "  :  "  A  C  K  "  ,
       = 31     └──────────────── 31 bytes of body ─────────────
                {"type":"ACK","messageId":"m1"}
```

---

## 3. The three conventions, and where they live in code

Both ends must agree on all three. Disagreement on any one desynchronises the stream
**permanently**, because every later boundary is then wrong too.

### 3.1 Four bytes

A Java `int`, so `writeInt`/`readInt` handle it with no manual byte assembly.

Four bytes can express ~2 GiB. That is **the format's capability**, not a limit we use —
our **policy limit** is `maxFrameBytes`, default 64 KiB, enforced in code. Two bytes would
have covered 64 KiB with no headroom *and* welded the wire format to today's policy:
raising the limit later would then be a breaking protocol change.

| Where | Line |
|---|---|
| Written | `out.writeInt(body.length)` — [FrameCodec.java:111](../src/main/java/com/eunangavin/relay/protocol/FrameCodec.java#L111) |
| Read | `length = in.readInt()` — [FrameCodec.java:133](../src/main/java/com/eunangavin/relay/protocol/FrameCodec.java#L133) |
| Policy limit | `maxFrameBytes` — [FrameCodec.java:82](../src/main/java/com/eunangavin/relay/protocol/FrameCodec.java#L82) |

### 3.2 Big-endian (network byte order)

Most significant byte first.

```
The number 58, two ways:

  Big-endian    (network order) :  00 00 00 3A   ← we use this
  Little-endian (x86 native)    :  3A 00 00 00
```

Read `00 00 00 3A` as little-endian and you get `0x3A000000` = **973,078,528** — "the next
frame is 973 MB" — which trips the bound check and kills the connection.

We get this for free: `DataOutputStream.writeInt` and `DataInputStream.readInt` are
*specified* big-endian, so there is no `ByteBuffer.order(...)` call anywhere.

| Where | Line |
|---|---|
| Both sides, implicitly | [FrameCodec.java:111](../src/main/java/com/eunangavin/relay/protocol/FrameCodec.java#L111) and [:133](../src/main/java/com/eunangavin/relay/protocol/FrameCodec.java#L133) |
| Asserted in test | `writesFourByteBigEndianLengthPrefix` — `FrameCodecTest.java` |

### 3.3 Length excludes the prefix itself

```
  Our convention:        length = 58  → after the 4 prefix bytes, read 58 more
  Other convention:      length = 62  → total including prefix; read 62 − 4 = 58
```

Same bytes on the wire either way — but pick one and never mix them. This is a classic
interop bug, and `maxFrameBytes` therefore bounds the **body**, not the total.

| Where | Line |
|---|---|
| Written as body length | [FrameCodec.java:111](../src/main/java/com/eunangavin/relay/protocol/FrameCodec.java#L111) |
| Allocation sized by it | `new byte[length]` — [FrameCodec.java:156](../src/main/java/com/eunangavin/relay/protocol/FrameCodec.java#L156) |
| Asserted in test | `assertEquals(wire.length - 4, declared)` |

---

## 4. The two traps the prefix creates

A length prefix solves framing but hands a hostile client a number we are about to trust.

### 4.1 Bound before allocate

```
  length = in.readInt()          ← this int came from the network
      │
      ├── negative?   new byte[-1]           → NegativeArraySizeException
      │                                        (unchecked crash, not a protocol fault)
      │
      └── enormous?   new byte[2_000_000_000] → OutOfMemoryError
                                               (one 4-byte write kills the server
                                                for every connected client)
```

So the check sits **between** the read and the allocation —
[FrameCodec.java:151](../src/main/java/com/eunangavin/relay/protocol/FrameCodec.java#L151),
immediately above `new byte[length]` on
[:156](../src/main/java/com/eunangavin/relay/protocol/FrameCodec.java#L156).

The same ordering logic applies on the write side: the bound is checked at
[:104](../src/main/java/com/eunangavin/relay/protocol/FrameCodec.java#L104), **before**
`writeInt`. Writing a prefix and only then discovering the body is too large would commit
a promise we cannot keep and desynchronise the stream. A rejected frame must be a no-op.

### 4.2 Short reads

`read(byte[])` returns however many bytes happen to be available — possibly one. It is
under no obligation to fill the array.

```java
byte[] body = new byte[len];
in.read(body);       // BUG: may fill only part of the array
in.readFully(body);  // correct: loops until full or end of stream
```

`readFully` at
[FrameCodec.java:164](../src/main/java/com/eunangavin/relay/protocol/FrameCodec.java#L164)
is the single call that handles TCP segmentation. Using `read` here is the most common bug
in hand-rolled framing — and it passes every localhost test.

Covered by `readsFrameSplitAcrossReads`, which wraps the stream in a decorator returning
one byte per call. That reproduces segmentation deterministically, on one thread, with no
sockets.

---

## 5. Frame reference

```
┌─────────────────┬──────────────────────────────────┬─────────────────────────┐
│ CLIENT → SERVER │ fields                           │ response                │
├─────────────────┼──────────────────────────────────┼─────────────────────────┤
│ REGISTER        │ clientId                         │ REGISTERED / ERROR      │
│ SEND            │ messageId, to, payload           │ ACCEPTED / REJECTED     │
│ ACK             │ messageId                        │ ACK_OK / ERROR          │
└─────────────────┴──────────────────────────────────┴─────────────────────────┘

┌─────────────────┬──────────────────────────────────┬─────────────────────────┐
│ SERVER → CLIENT │ fields                           │ when                    │
├─────────────────┼──────────────────────────────────┼─────────────────────────┤
│ REGISTERED      │ clientId, pending                │ after REGISTER          │
│ ACCEPTED        │ messageId                        │ message is in mailbox   │
│ REJECTED        │ messageId, code, reason          │ nothing was queued      │
│ ACK_OK          │ messageId                        │ after ACK (idempotent)  │
│ ERROR           │ code, reason                     │ fault, no message id    │
│ DELIVER         │ messageId, from, payload         │ unsolicited push        │
│ SHUTDOWN        │ reason                           │ server going away       │
└─────────────────┴──────────────────────────────────┴─────────────────────────┘
```

Defined in
[`Frame.java`](../src/main/java/com/eunangavin/relay/protocol/Frame.java) as a sealed
interface with the records nested inside it. Error codes in
[`ErrorCode.java`](../src/main/java/com/eunangavin/relay/protocol/ErrorCode.java).

---

## 6. The one-sentence version

> TCP guarantees my bytes arrive in order — it just doesn't know where my messages start
> and stop, because it splits the stream on network boundaries, not application ones. The
> 4-byte big-endian length prefix is how I put those boundaries back, and checking it
> before I allocate is what stops a hostile client turning that prefix into an
> `OutOfMemoryError`.
