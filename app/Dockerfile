# ---- Build stage ----
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build

# Cache dependencies first
COPY pom.xml .
RUN mvn -B -q dependency:go-offline

COPY src ./src
RUN mvn -B -q clean package -DskipTests

# ---- Runtime stage ----
FROM eclipse-temurin:21-jre
WORKDIR /app

# OpenTelemetry Java agent — the OTel-recommended zero-code auto-instrumentation
# for traces, metrics and logs.
ADD https://github.com/open-telemetry/opentelemetry-java-instrumentation/releases/download/v2.30.0/opentelemetry-javaagent.jar /app/opentelemetry-javaagent.jar

# Pyroscope Java agent 2.1.x — the profiler (async-profiler). It runs the
# continuous profiling and pushes the data out.
ADD https://github.com/grafana/pyroscope-java/releases/download/v2.1.2/pyroscope.jar /app/pyroscope.jar

# grafana/otel-profiling-java — a small sidecar extension for the OTel agent. It
# bridges to the Pyroscope agent above (via the system classloader) and tags
# every profile with the active span, so a Tempo span links to its flame graph.
ADD https://repo1.maven.org/maven2/io/pyroscope/otel/1.0.4/otel-1.0.4.jar /app/pyroscope-otel.jar

COPY --from=build /build/target/app.jar /app/app.jar

# Attach the agents via JAVA_TOOL_OPTIONS (entrypoint stays a plain `java -jar`).
# Order matters: the Pyroscope agent MUST come before the OTel agent so its
# ProfilerSdk is on the system classpath by the time the OTel agent's premain
# builds the SDK and the bridge extension looks the profiler up (via the system
# classloader). With the reverse order the bridge falls back and span labels are
# lost.
ENV JAVA_TOOL_OPTIONS="-javaagent:/app/pyroscope.jar -javaagent:/app/opentelemetry-javaagent.jar -Dotel.javaagent.extensions=/app/pyroscope-otel.jar"

EXPOSE 8080

ENTRYPOINT ["java", "-jar", "/app/app.jar"]
