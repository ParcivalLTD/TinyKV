# -------------------------------------------------------------
# Stage 1: Build & Package Application
# -------------------------------------------------------------
FROM eclipse-temurin:21-jdk-alpine AS builder

WORKDIR /workspace

# Cache Gradle wrapper and dependencies
COPY gradlew gradlew.bat settings.gradle build.gradle ./
COPY gradle ./gradle

# Make gradlew executable and download dependencies
RUN chmod +x gradlew && ./gradlew dependencies --no-daemon

# Copy source code and build application distribution
COPY src ./src
RUN ./gradlew installDist -x test --no-daemon

# -------------------------------------------------------------
# Stage 2: Lean Runtime Image
# -------------------------------------------------------------
FROM eclipse-temurin:21-jre-alpine AS runner

# Create non-root system group and user
RUN addgroup -g 10001 -S tinykv && \
    adduser -u 10001 -S tinykv -G tinykv

# Create data directory for WAL segments and hint files
RUN mkdir -p /data && chown -R tinykv:tinykv /data

WORKDIR /opt/tinykv

# Copy built distribution from builder
COPY --from=builder --chown=tinykv:tinykv /workspace/build/install/tinykv ./

# Default configuration environment variables
ENV TINYKV_PORT=8080 \
    TINYKV_DATA_DIR=/data \
    TINYKV_SYNC_POLICY=GROUP_COMMIT \
    TINYKV_MAX_SEGMENT_MB=16

# Expose HTTP REST port
EXPOSE 8080

# Persist storage volume
VOLUME ["/data"]

USER tinykv:tinykv

# Health check using the liveness endpoint
HEALTHCHECK --interval=5s --timeout=3s --start-period=5s --retries=3 \
  CMD wget -q -O - http://localhost:8080/healthz || exit 1

ENTRYPOINT ["/opt/tinykv/bin/tinykv"]
