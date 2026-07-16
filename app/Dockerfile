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

# Grafana Pyroscope Java agent (latest release). Attached via -javaagent,
# it profiles the JVM continuously and pushes directly to Pyroscope.
ADD https://github.com/grafana/pyroscope-java/releases/latest/download/pyroscope.jar /app/pyroscope.jar

COPY --from=build /build/target/app.jar /app/app.jar

EXPOSE 8080

ENTRYPOINT ["java", "-javaagent:/app/pyroscope.jar", "-jar", "/app/app.jar"]
