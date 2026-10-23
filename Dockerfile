# syntax=docker/dockerfile:1.7
# TinQa Procurement Backend (Spring Boot 3.5, Java 21)
#   docker build -t tinqa-procurement-backend .
#   docker run -p 9090:9090 --env-file procurement.env -v tinqa-procurement-uploads:/app/uploads tinqa-procurement-backend

# ---------- Stage 1: build the jar ----------
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build

# Resolve dependencies first so they are cached until pom.xml changes
COPY pom.xml .
RUN --mount=type=cache,target=/root/.m2 mvn -B -q dependency:go-offline

COPY src ./src
COPY quality ./quality
# `package` also runs the quality gate (quality/QualityGate.java); add -Dquality.strict=true to fail on hard-coded secrets
RUN --mount=type=cache,target=/root/.m2 mvn -B -q package -DskipTests \
 && cp target/*.jar /build/app.jar \
 && java -Djarmode=tools -jar /build/app.jar extract --layers --launcher --destination /build/extracted

# ---------- Stage 2: runtime ----------
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app

RUN addgroup -S tinqa && adduser -S tinqa -G tinqa \
 && mkdir -p /app/uploads && chown -R tinqa:tinqa /app

# Spring Boot layers: least-changing first for better image layer reuse
COPY --from=build --chown=tinqa:tinqa /build/extracted/dependencies/ ./
COPY --from=build --chown=tinqa:tinqa /build/extracted/spring-boot-loader/ ./
COPY --from=build --chown=tinqa:tinqa /build/extracted/snapshot-dependencies/ ./
COPY --from=build --chown=tinqa:tinqa /build/extracted/application/ ./

ENV SPRING_PROFILES_ACTIVE=prod \
    FILE_UPLOAD_DIR=/app/uploads \
    JAVA_OPTS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError"

# Uploaded documents must survive container restarts
VOLUME ["/app/uploads"]

USER tinqa
EXPOSE 9090

HEALTHCHECK --interval=30s --timeout=5s --start-period=90s --retries=3 \
  CMD wget -qO- http://127.0.0.1:9090/api/actuator/health | grep -q '"UP"' || exit 1

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS org.springframework.boot.loader.launch.JarLauncher"]
