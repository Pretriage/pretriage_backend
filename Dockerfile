# syntax=docker/dockerfile:1
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /workspace
COPY pom.xml ./
COPY src ./src
RUN --mount=type=cache,target=/root/.m2 mvn -B -ntp -Dmaven.test.skip=true package

FROM eclipse-temurin:21-jre-jammy AS runtime
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/* \
    && groupadd --gid 10001 app \
    && useradd --uid 10001 --gid app --no-create-home app
WORKDIR /app
COPY --from=build --chown=app:app /workspace/target/backend-*.jar /app/backend.jar
USER app
ENV SPRING_PROFILES_ACTIVE=aws \
    JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=65.0 -XX:+ExitOnOutOfMemoryError -Dfile.encoding=UTF-8"
EXPOSE 8080
HEALTHCHECK --interval=30s --timeout=5s --start-period=120s --retries=3 \
    CMD curl --fail --silent http://localhost:8080/actuator/health/readiness || exit 1
ENTRYPOINT ["java", "-jar", "/app/backend.jar"]
