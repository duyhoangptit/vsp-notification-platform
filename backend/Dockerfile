# syntax=docker/dockerfile:1.7
# Build context = repo root (pom.xml copies ../specs/001-notification-hub/contracts into the jar):
#   docker build -f backend/Dockerfile -t pnh-backend .

# ─── Build ────────────────────────────────────────────────────────────────────
FROM eclipse-temurin:21-jdk AS build
WORKDIR /src/backend

# Resolve dependencies first so they are cached between builds that only touch sources
COPY backend/.mvn/ .mvn/
COPY backend/mvnw backend/pom.xml ./
# Best effort: go-offline cannot resolve everything (and flaky mirrors happen); `package` fetches the rest.
RUN --mount=type=cache,target=/root/.m2 ./mvnw -B -q dependency:go-offline || true

COPY specs/001-notification-hub/contracts/ /src/specs/001-notification-hub/contracts/
COPY backend/src/ src/
# Tests/format/coverage run in the CI workflow; the image build only packages.
RUN --mount=type=cache,target=/root/.m2 \
    ./mvnw -B -q package -DskipTests -DskipITs -Djacoco.skip=true -Dspotless.check.skip=true \
 && java -Djarmode=tools -jar target/pnh.jar extract --layers --launcher --destination /extracted

# ─── Runtime ──────────────────────────────────────────────────────────────────
FROM eclipse-temurin:21-jre
RUN apt-get update \
 && apt-get install -y --no-install-recommends curl \
 && rm -rf /var/lib/apt/lists/* \
 && groupadd --system pnh && useradd --system --gid pnh --home /app pnh
WORKDIR /app

COPY --from=build /extracted/dependencies/ ./
COPY --from=build /extracted/spring-boot-loader/ ./
COPY --from=build /extracted/snapshot-dependencies/ ./
COPY --from=build /extracted/application/ ./

USER pnh
EXPOSE 8080
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError"

HEALTHCHECK --interval=15s --timeout=5s --start-period=90s --retries=5 \
  CMD curl -fsS http://localhost:8080/actuator/health/readiness || exit 1

ENTRYPOINT ["java", "org.springframework.boot.loader.launch.JarLauncher"]
