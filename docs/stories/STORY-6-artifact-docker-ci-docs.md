# STORY-6 — Artifact, Docker, CI, and the graded docs

| | |
|---|---|
| **Epic** | EPIC-3 — Ship it |
| **Priority** | P0 — `README.md` and `APPROACH.md` are *named deliverables* |
| **Points** | 5 |
| **Status** | To Do |
| **Depends on** | STORY-5 (something to run) |

## 1. Goal

One runnable jar, one small image, one CI workflow — and the two documents the brief
actually grades.

After this story `git clone && ./mvnw verify && java -jar target/relay-1.0.0.jar server`
works on a machine that has never seen this project, and `docs/DEMO.md` loses its classpath
preamble entirely.

## 2. Why this matters

**Two of these are graded deliverables and the rest are not.** Keep that proportion:

> *"Repository, Source and tests, README.md, APPROACH.md"*

> *"CI, deployment, and distributed-system implementation are **discussion topics, not
> requirements** for this exercise."*

So: `README.md` and `APPROACH.md` get real effort. The jar is worth it because it removes
friction from the demo and answers "what is the artifact?". Docker is bonus 2 and cheap.
CI is explicitly not required — a 25-line workflow that feeds the "source to artifact"
conversation is enough, and anything more is wasted.

**The reproduction risk is the real reason for the jar.** The brief says *"no laptop is
needed"* and the repo *"must reproduce in the interview environment using the documented
commands"*. Right now the demo needs a hand-assembled classpath whose separator differs by
platform — that is exactly the kind of thing that eats four minutes in front of an
audience.

## 3. Concepts & Theory

### 3.1 A plain jar is not runnable

`mvn package` already produces `target/relay-1.0.0.jar`, and it does not work:

```
$ java -jar target/relay-1.0.0.jar server
no main manifest attribute, in target/relay-1.0.0.jar
```

Two separate things are missing, and fixing only the first swaps one error for another.

**No `Main-Class` in the manifest.** `java -jar` reads exactly that attribute to know where
to start. Without it the JVM has nothing to run.

**No dependencies inside.** The jar contains 38 entries and zero Jackson classes — Maven
does not bundle dependencies into a plain jar. Add `Main-Class` alone and you trade the
manifest error for `NoClassDefFoundError: com/fasterxml/jackson/databind/json/JsonMapper`
on the first frame you encode.

An **uber jar** (or *fat* jar, or *shaded* jar) solves both: it unpacks every dependency's
classes into one archive and writes the manifest entry. `maven-shade-plugin` does it in one
step, bound to the `package` phase.

The alternative worth being able to name: `maven-assembly-plugin` with the
`jar-with-dependencies` descriptor does roughly the same thing. Shade is preferred because
it can also *relocate* packages to avoid version clashes between dependencies — irrelevant
with one dependency, but it is why shade is the conventional choice.

### 3.2 Exec form versus shell form — the trap that will embarrass you

This is the single most important thing in the story.

```dockerfile
ENTRYPOINT java -jar /app/relay.jar server          # SHELL form  - broken
ENTRYPOINT ["java", "-jar", "/app/relay.jar", "server"]   # EXEC form - correct
```

**Shell form** wraps your command: Docker actually runs `/bin/sh -c "java -jar ..."`. The
shell becomes PID 1; the JVM is a child. When `docker stop` sends `SIGTERM`, it goes to PID
1 — the shell — which does not forward it. The JVM never hears it, the shutdown hook never
fires, `docker stop` waits out its full 10-second grace period and then `SIGKILL`s
everything.

So: no `SHUTDOWN` frames to clients, no draining, no thread cleanup. **Every piece of
graceful shutdown work in `RelayServer.close()` is silently skipped**, and the only symptom
is that `docker stop` feels slow.

**Exec form** runs the JVM directly as PID 1, so `SIGTERM` reaches it and the shutdown hook
in `Main` runs.

This is worth demonstrating live: `docker stop` should return in well under a second, and
the container log should show the shutdown sequence. If it takes ten seconds, the entrypoint
is wrong.

### 3.3 Multi-stage builds

```
   Stage 1 "build"     maven:3.9-eclipse-temurin-21   ~800 MB   compiles, produces the jar
        │  COPY --from=build
        ▼
   Stage 2 (final)     eclipse-temurin:21-jre         ~270 MB   just the JRE and the jar
```

Only the final stage becomes the image. The JDK, Maven, the `.m2` cache and every source
file are discarded.

The trade-off, worth stating: a multi-stage build is self-contained — `docker build .` works
from a clean clone with no prior `mvn` run — but it recompiles from scratch each time. The
alternative (`COPY target/*.jar`) is much faster and makes the image depend on a build step
you have to remember to document. **Self-contained wins here**, because "reproduces from a
clean clone" is an explicit requirement.

Cache the dependency download by copying `pom.xml` and running `dependency:go-offline`
before copying `src/` — otherwise every source edit re-downloads the world.

### 3.4 Container networking for the demo

```bash
docker network create relay-demo
docker run --rm --network relay-demo --name relay-server -p 9090:9090 relay:latest server
docker run --rm -it --network relay-demo -e RELAY_HOST=relay-server relay:latest client alice
```

Three things make this work, and each fails differently if missed:

- **A user-defined bridge network** gives automatic DNS between containers, so `relay-server` resolves by container name. The default bridge does not.
- **`RELAY_HOST`** is why STORY-5 added `clientHost()`. Without it every client tries `localhost`, which inside a container is the container itself.
- **The server binds `0.0.0.0`**, not `localhost`. Bound to loopback, nothing outside the container could ever reach it.

**Avoid `--network host`.** It behaves differently on Linux (works), macOS and Windows
(Docker Desktop runs a Linux VM, so "host" is the VM, not your machine). It will fail in the
interview environment if that machine is not Linux.

### 3.5 The Maven wrapper

Maven is **not on this machine's PATH** — the only copies are inside IntelliJ and a wrapper
distribution under `~/.m2`. So a README that says `mvn verify` documents a command that does
not work here, and may not work on the interview machine either.

`./mvnw` fixes it: `mvn wrapper:wrapper` commits a small script plus a properties file, and
it downloads the pinned Maven version on first use. **It removes a prerequisite rather than
documenting one**, which is precisely what "must reproduce in the interview environment"
asks for.

Java 21+ remains a genuine prerequisite. State it, and note that Docker removes even that.

### 3.6 What CI is for here

The brief says CI is a discussion topic, not a requirement. So build the smallest thing that
makes the discussion concrete:

```
   push ──▶ checkout ──▶ setup JDK 21 ──▶ ./mvnw -B verify ──▶ upload target/*.jar
```

That gives you a real answer to *"walk me from source to artifact"*: push triggers the
workflow, the full suite runs, the shade plugin produces one jar, and CI attaches it to the
run so it is downloadable from any commit.

**Be precise about one thing, because it is a fair follow-up:** the Docker image does *not*
consume that CI artifact — the multi-stage build in §6.2 compiles from source again. That is
a deliberate trade. Self-contained (`docker build` works from a clean clone with no prior
`mvn` run) beats "the exact bytes CI tested", at this scale. In a real pipeline you would
push the CI-built jar to a registry and have the image copy it, so the tested bytes are the
shipped bytes. Saying that unprompted is a better answer than pretending the simple version
is the rigorous one.

Resist adding matrix builds, coverage gates, or release automation. None of it is graded and
all of it is more to explain.

## 4. Design & Approach

### What ships in the public repo

A decision, not an oversight — and worth stating in `APPROACH.md`.

| Path | Ships? | Why |
|---|---|---|
| `src/`, `pom.xml`, `Dockerfile`, `.github/` | **Yes** | The submission |
| `README.md`, `APPROACH.md` | **Yes** | Named deliverables |
| `docs/adr/` | **Yes** | Five ADRs; the reasoning `APPROACH.md` draws on. Genuinely supports the submission |
| `docs/PROTOCOL.md`, `docs/DEMO.md` | **Yes** | The wire format and how to run the demo. Both useful to a reader |
| `docs/stories/` | **Probably not** | ~4,600 lines of teaching specs for a two-hour exercise. Learning material, not a deliverable — and the proportion invites the wrong question |
| `docs/INTERVIEW-PLAN.md` | **Never** | Already gitignored. Predicted questions with rehearsed answers would read very badly to an interviewer browsing the repo |

The story specs are a genuine judgement call. Shipping them shows the spec-driven method,
which is on your CV — but 4,600 lines of specs against 919 lines of code is a proportion
that draws attention to scope rather than to rigour. **Leaning: keep them local, mention the
method in `APPROACH.md`.**

### The two documents

Neither should be written from scratch. Both assemble from work already done — §6.4 and
§6.5 map every section to its source.

**Write them yourself.** `APPROACH.md` is your reasoning, and it contains the AI-usage
disclosure. It is also the best comprehension exercise available: if you can write a
section, you understand it; if you stall, that is exactly the gap to close before Thursday.

## 5. Files to create / modify

| Path | Purpose |
|---|---|
| `pom.xml` | **Modify:** add `maven-shade-plugin` with `Main-Class` |
| `mvnw`, `mvnw.cmd`, `.mvn/wrapper/` | **Create** via `mvn wrapper:wrapper` |
| `Dockerfile` | **Create.** Multi-stage, exec-form entrypoint, non-root user |
| `.dockerignore` | **Create.** Keep `target/` and `.git/` out of the build context |
| `.github/workflows/ci.yml` | **Create.** ~25 lines |
| `.gitignore` | **Modify:** add `.idea/` and `*.iml` |
| `README.md` | **Create.** Graded |
| `APPROACH.md` | **Create.** Graded |
| `docs/DEMO.md` | **Modify:** replace the classpath preamble with `java -jar` |

## 6. Implementation

### 6.1 Shade plugin

Seventeen lines, and that is the floor. Verified working — `java -jar` runs both
subcommands off it.

```xml
<plugin>
    <groupId>org.apache.maven.plugins</groupId>
    <artifactId>maven-shade-plugin</artifactId>
    <version>3.6.2</version>
    <executions>
        <execution>
            <goals><goal>shade</goal></goals>
            <configuration>
                <!-- Default is true, which writes a stray dependency-reduced-pom.xml
                     into the project root. Everything else here is already the default. -->
                <createDependencyReducedPom>false</createDependencyReducedPom>
                <transformers>
                    <transformer implementation="org.apache.maven.plugins.shade.resource.ManifestResourceTransformer">
                        <mainClass>com.eunangavin.relay.Main</mainClass>
                    </transformer>
                </transformers>
            </configuration>
        </execution>
    </executions>
</plugin>
```

**Do not add `<phase>package</phase>` or `<shadedArtifactAttached>false</shadedArtifactAttached>`** —
both restate defaults and just make the block look more complicated than it is.

#### Why it looks so nested

The outer layers are Maven's model, not shade's: a plugin can be bound several times with
different settings, so `plugin → executions → execution → goals + configuration` is the
shape even when you bind it once.

`<transformers>` is the part worth understanding, because it looks gratuitous and is not.
Merging N jars into one means **files collide** — every jar has a `META-INF/MANIFEST.MF`,
several may have `META-INF/services/` registrations. Shade cannot just keep one and drop
the rest; for service files that silently breaks things. So it needs a policy per collision
type, and a transformer *is* that policy. Setting `Main-Class` is a side effect of the
transformer that owns the manifest.

We need only that one. `ServicesResourceTransformer` (concatenates service files) and
`AppendingTransformer` exist for cases we do not have.

#### Alternatives, and why none is simpler

| Approach | Verdict |
|---|---|
| `maven-jar-plugin` + `addClasspath` | Shorter, but the jar is **not** self-contained — it writes a `Class-Path` pointing at jars that must sit beside it, so it also needs `dependency:copy-dependencies`. Two plugins, no longer one artifact. |
| `maven-assembly-plugin` with `jar-with-dependencies` | Same length, and it emits a second jar with an awkward name. Worse when asked "what is the artifact?" |
| No plugin, run with `-cp` | What we are escaping. |

#### One side effect to know about

Shade leaves `original-relay-1.0.0.jar` in `target/` — the pre-shade jar. Harmless, but be
ready to say which one ships: `relay-1.0.0.jar`, the 2.3 MB one with Jackson inside.

### 6.2 `Dockerfile`

```dockerfile
# ---- build ----------------------------------------------------------------
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /src
# Copy the pom alone first so the dependency layer is cached across source edits.
COPY pom.xml .
RUN mvn -B -q dependency:go-offline
COPY src ./src
RUN mvn -B -q clean package -DskipTests

# ---- runtime --------------------------------------------------------------
FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /src/target/relay-*.jar /app/relay.jar

# Nothing here needs root.
RUN useradd --system --no-create-home relay
USER relay

EXPOSE 9090

# EXEC form, not shell form. Shell form would run this under /bin/sh, which becomes PID 1
# and does not forward SIGTERM - so `docker stop` would never reach the JVM, the shutdown
# hook would never fire, and the container would be SIGKILLed after the grace period.
ENTRYPOINT ["java", "-jar", "/app/relay.jar"]
CMD ["server"]
```

`ENTRYPOINT` plus `CMD` is what lets `docker run relay:latest client alice` override the
subcommand while keeping the `java -jar` prefix fixed.

### 6.3 `.github/workflows/ci.yml`

```yaml
name: build
on: [push, pull_request]

jobs:
  build:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with:
          distribution: temurin
          java-version: '21'
          cache: maven
      - run: ./mvnw -B verify
      - uses: actions/upload-artifact@v4
        with:
          name: relay-jar
          path: target/relay-*.jar
```

Note `java-version: '21'`, not 25 — the artifact targets 21, and CI should build what ships.

### 6.4 `README.md` — the outline, and where each part comes from

The brief asks for: *prerequisites; exact build, run and test commands; the artifact
produced; and how ports, timeouts, dependencies and server lifecycle are controlled.*

| Section | Source |
|---|---|
| What it is (3 sentences) | STORY-1 §13, the five-sentence pitch |
| Prerequisites | Java 21+. Maven not needed (`./mvnw`). Docker path needs only Docker |
| Build / test / run | `./mvnw verify`, `java -jar target/relay-1.0.0.jar server`, and the Docker commands from §3.4 |
| The artifact | `target/relay-1.0.0.jar` — a shaded uber jar, `Main-Class` set, both subcommands |
| Configuration | The six bounds plus `RELAY_HOST`, straight out of `RelayConfig` — table of variable, default, and what breaching it does |
| Server lifecycle | Start, `SIGTERM`/Ctrl-C, the shutdown hook, the five-step sequence |
| Protocol summary | Link `docs/PROTOCOL.md`; do not duplicate it |
| Demo | Link `docs/DEMO.md` |
| Project layout | The file tree with one line per file |

Keep it operational. The *reasoning* belongs in `APPROACH.md`.

### 6.5 `APPROACH.md` — the outline, and where each part comes from

The brief asks for: *acceptance criteria; architecture, protocol, state, and concurrency
models; delivery semantics; trade-offs; known limitations; next steps; and AI-tool usage.*

Almost all of it exists already:

| Section | Assemble from |
|---|---|
| Acceptance criteria | The seven core requirements, each with the test that proves it — §8 of stories 2–5 |
| Architecture | `OVERVIEW.md` §3 diagrams; the `net → session → protocol` dependency rule |
| Protocol model | `ADR-002` + `PROTOCOL.md` |
| State model | STORY-3 §3.1 — identity outlives connection, pending vs inflight |
| Concurrency model | `ADR-001` (virtual threads), STORY-2 §3.1–3.3 (two threads, bounded queue, unblocking) |
| Delivery semantics | `ADR-005` — at-least-once *with redelivery on reconnect*, per-recipient FIFO in acceptance order |
| Trade-offs | The "deviations" tables, §11a of each story |
| Known limitations | Below — the section that *gains* marks |
| Next steps | Ack timeout · bounded LRU dedupe · idle-session expiry · persistence · `correlationId` · metrics |
| AI-tool usage | Yours to write. See below |

**Known limitations — state all of these plainly:**

- In-memory only; a restart loses every queued message
- No authentication or encryption — any client can claim any identity
- Single server; the registry is process-local by design
- Sessions are never reclaimed; many unique ids grow memory without bound
- Duplicate detection is bounded to live ids — a reused id after eviction is accepted
- No ack timeout, so redelivery is triggered by reconnect only
- Half-open connections are detected only on the next write
- Payloads are UTF-8 strings, not arbitrary bytes

**On scope, say it before they ask.** Something like: *"This went past the two-hour guide.
Held strictly to it I would have shipped the core (stories 1–3) and documented FIFO and the
client as next steps. I kept going because being able to demonstrate the behaviour helps
explain the design."* Naming the overrun yourself converts your biggest vulnerability into
evidence of scope judgement — the thing they say they are grading.

**AI usage is a named deliverable.** Write what actually happened, accurately. Being able to
back it up is what makes it neutral; overstating your own share is the only genuinely
dangerous option. Two things worth including because they are true and they are yours: the
mutation check that found four ordering tests passing against a deliberately broken
implementation, and the probe that showed virtual threads *do* unblock on interrupt,
contradicting the comment you had already written.

## 7. Gotchas & pitfalls

1. **Shell-form `ENTRYPOINT`** (§3.2). The one that will embarrass you live. Verify with `time docker stop` — under a second, not ten.
2. **Binding `localhost` inside the container** makes it unreachable from outside. `RelayServer` already binds `0.0.0.0`; do not "fix" it.
3. **`--network host`** works on Linux and not on macOS or Windows. Use a user-defined bridge.
4. **Forgetting `RELAY_HOST`** on client containers — they will try `localhost`, which is themselves.
5. **`dependency-reduced-pom.xml`** appearing in the project root — turn it off in the shade config.
6. **CI on Java 25 while the artifact targets 21.** Build what ships.
7. **No `.dockerignore`** means the build context includes `target/` and `.git/` — slow, and it defeats layer caching.
8. **`.idea/` is not gitignored yet.** IDE config in a public submission repo looks careless.
9. **Do not verify the clean clone in the project directory.** Clone into a *new* folder, with `~/.m2` untouched, and run the README commands verbatim.
10. **Ship the docs decision deliberately** (§4), and say which way you went in `APPROACH.md`.

## 8. Acceptance Criteria

- [ ] `./mvnw clean verify` succeeds from a clean clone with Maven not installed.
- [ ] `java -jar target/relay-1.0.0.jar server` starts and prints the effective config.
- [ ] `java -jar target/relay-1.0.0.jar client alice` connects and registers.
- [ ] The jar contains Jackson's classes and a `Main-Class` manifest entry.
- [ ] `docker build -t relay:latest .` succeeds from a clean clone with no prior `mvn` run.
- [ ] The three-container demo (§3.4) works: server plus two clients on a user-defined bridge.
- [ ] **`docker stop` returns in under a second and the container log shows the shutdown sequence.**
- [ ] The CI workflow runs the suite and uploads the jar.
- [ ] `README.md` covers every item the brief lists.
- [ ] `APPROACH.md` covers every item the brief lists, including AI usage and known limitations.
- [ ] `.gitignore` excludes `.idea/`, `*.iml`, `target/`, and `docs/INTERVIEW-PLAN.md`.
- [ ] `docs/DEMO.md` uses `java -jar`, with no classpath preamble.
- [ ] A fresh clone into a new directory reproduces everything using only the documented commands.
- [ ] No secrets, no proprietary code anywhere in the history.

## 9. Tests to write

**None.** Packaging, containers and CI are not unit-testable, and inventing tests for them
would be theatre. Verification here is the manual drill in §10, and CI itself is the
regression check — a broken build fails visibly on every push.

Say that plainly if asked: *"the test boundary stops at the JVM. Everything past it is
verified by running it, and CI runs it on every push."*

## 10. Manual verification — the clean-clone drill

**Do this on Wednesday, not Thursday morning.** It is the single highest-value check in the
project, and the brief asks for it explicitly.

```bash
cd /tmp                      # a directory that has never seen this project
git clone <your-public-url> relay-check
cd relay-check

./mvnw clean verify                                  # expect: 88 tests, BUILD SUCCESS
java -jar target/relay-1.0.0.jar server              # expect: config printed, listening
```

Then, in two more terminals from the same clone, run the nine beats from `docs/DEMO.md`.

Then the Docker path:

```bash
docker build -t relay:latest .
docker network create relay-demo
# NOTE: no --rm on the server. --rm deletes the container the moment it exits, so
# `docker logs` after a stop would fail with "No such container".
docker run -d --network relay-demo --name relay-server -p 9090:9090 relay:latest server
docker run --rm -it --network relay-demo -e RELAY_HOST=relay-server relay:latest client alice

time docker stop relay-server        # expect: WELL under a second
docker logs relay-server             # expect: the shutdown sequence
docker rm relay-server               # tidy up
```

If `docker stop` takes ten seconds, the entrypoint is in shell form. Fix it before anything
else.

## 11. Out of scope

| Not in this story | Why |
|---|---|
| Persistence | STORY-7, stretch, almost certainly unbuilt |
| Kubernetes, compose, deployment | The brief calls deployment a discussion topic |
| Coverage gates, matrix builds, release automation | Not graded; more to explain |
| Publishing to a registry | Not asked for |
| Migrating `TestClient` onto `RelayClient` | Deliberate scope call from STORY-5 |

## 12. Definition of Done

- [ ] Compiles (`./mvnw clean compile`)
- [ ] All tests green (`./mvnw test`)
- [ ] Every acceptance criterion met
- [ ] The clean-clone drill passes, both paths
- [ ] `docker stop` timed and under a second
- [ ] Repo is public, no secrets, `.idea/` excluded
- [ ] Sections 13 and 14 added
- [ ] Spec reconciled against the implementation, deviations in §11a
