# Multi-stage: the build stage carries a JDK and Maven (~800 MB) and is thrown away.
# Only the second stage becomes the image, so it ships a JRE and one jar.
#
# The two stages exist so that `docker build .` works from a clean clone with no prior
# Maven run. A single-stage `COPY target/*.jar` would be four lines, but it would need
# Java and Maven on the host - which removes the whole point of having a Docker path.
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /src
COPY . .
RUN mvn -B -q package -DskipTests

FROM eclipse-temurin:21-jre
COPY --from=build /src/target/relay-1.0.0.jar /app/relay.jar

# Documentation only - it does not publish anything. Use -p to actually map the port.
EXPOSE 9090

# EXEC form, not shell form, and this matters.
#
# Shell form (ENTRYPOINT java -jar ...) runs the JVM under /bin/sh. The shell becomes
# PID 1 and does not forward SIGTERM, so `docker stop` never reaches the JVM: the
# shutdown hook never fires, no SHUTDOWN frames are sent, nothing drains, and the
# container is SIGKILLed after the full grace period. The only visible symptom is that
# `docker stop` takes ten seconds.
#
# Exec form makes the JVM PID 1, so SIGTERM arrives and Main's shutdown hook runs.
ENTRYPOINT ["java", "-jar", "/app/relay.jar"]

# Overridable: `docker run relay:latest client alice` replaces this, keeping the prefix.
CMD ["server"]
