# ── Stage 1: Build the Vite frontend ─────────────────────────────────────────
# Outputs to /app/src/main/resources/static (vite outDir = "../src/main/resources/static"
# relative to frontend/). Maven build in stage 2 picks up these files and bundles them
# into the fat JAR — Spring Boot serves the SPA directly; nginx does NOT file-serve it.
FROM node:22-alpine AS frontend-build

WORKDIR /app

# Layer-cache node_modules separately from source
COPY frontend/package.json frontend/package-lock.json ./frontend/
RUN npm ci --prefix frontend

COPY frontend/ ./frontend/
# Privacy.tsx and Terms.tsx import docs/legal/*.md via Vite ?raw.
# The import resolves ../../../docs/legal/ from frontend/src/pages/ → /app/docs/legal/.
COPY docs/legal/ ./docs/legal/
RUN npm run build --prefix frontend
# Output now at /app/src/main/resources/static/


# ── Stage 2: Build the Spring Boot fat JAR ───────────────────────────────────
FROM maven:3.9-eclipse-temurin-21 AS jar-build

WORKDIR /app

# Resolve dependencies before copying source — keeps this layer cached
# unless pom.xml changes. -Dskip.frontend=true so the Maven frontend plugin
# (which downloads Node) does not run; we already have the assets from stage 1.
COPY pom.xml .
RUN mvn -q dependency:go-offline -Dskip.frontend=true

COPY src/ ./src/
# Overwrite the (empty) static dir with the Vite build output from stage 1
COPY --from=frontend-build /app/src/main/resources/static ./src/main/resources/static

RUN mvn -q package -DskipTests -Dskip.frontend=true


# ── Stage 3: Slim runtime image ───────────────────────────────────────────────
FROM eclipse-temurin:21-jre-alpine AS runtime

# Non-root user — minimal privilege at runtime
RUN addgroup -S appgroup && adduser -S appuser -G appgroup

WORKDIR /app

COPY --from=jar-build /app/target/*.jar app.jar
RUN chown appuser:appgroup app.jar

USER appuser

EXPOSE 8080

# Healthcheck — hits /actuator/health (Spring Boot Actuator, exposed via management config).
# start_period covers slow migrations and Flyway running against Supabase on cold start.
HEALTHCHECK --interval=30s --timeout=10s --start-period=90s --retries=3 \
  CMD wget -q --spider http://localhost:8080/actuator/health || exit 1

ENTRYPOINT ["java", \
  # Container-aware memory: let the JVM read cgroup limits.  75% of 4 GB = ~3 GB heap.
  # Leaves ~1 GB for OS, nginx-sidecar, and off-heap (Metaspace, threads, Netty).
  "-XX:+UseContainerSupport", \
  "-XX:MaxRAMPercentage=75.0", \
  # Belt-and-suspenders TZ: AppConfig already uses Clock.system(Africa/Cairo), but
  # the JVM default affects log timestamps, JDBC date literals, etc.
  "-Duser.timezone=Africa/Cairo", \
  # Avoid blocking on /dev/random for SecureRandom seed (common in containers).
  "-Djava.security.egd=file:/dev/./urandom", \
  "-jar", "app.jar"]
