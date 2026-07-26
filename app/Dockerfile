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

# grafana/otel-profiling-java, loaded as an OTel agent extension. It bundles the
# Pyroscope profiler (async-profiler) AND tags every profile with the active
# span, so traces link to flame graphs. No separate Pyroscope agent needed.
ADD https://repo1.maven.org/maven2/io/pyroscope/otel/1.0.4/otel-1.0.4.jar /app/pyroscope-otel.jar

COPY --from=build /build/target/app.jar /app/app.jar

# Attach the agent + extension via JAVA_TOOL_OPTIONS so the entrypoint stays a
# plain `java -jar`.
ENV JAVA_TOOL_OPTIONS="-javaagent:/app/opentelemetry-javaagent.jar -Dotel.javaagent.extensions=/app/pyroscope-otel.jar"

EXPOSE 8080

ENTRYPOINT ["java", "-jar", "/app/app.jar"]
