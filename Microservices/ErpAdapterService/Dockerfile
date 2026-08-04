# Build context is Microservices/, not ErpAdapterService/ — the composite build resolves asm-tenant-core
# through a relative path, so both directories must be visible here.

# ── Stage 1: Build ────────────────────────────────────────────────────────────
FROM gradle:8.5-jdk17 AS build
WORKDIR /workspace

# The shared module first: it changes far less often than the service, so editing service code
# does not invalidate its build cache.
COPY asm-tenant-core asm-tenant-core
WORKDIR /workspace/ErpAdapterService

ENV GRADLE_OPTS="-Dorg.gradle.internal.http.connectionTimeout=120000 -Dorg.gradle.internal.http.socketTimeout=120000"

COPY ErpAdapterService/settings.gradle ErpAdapterService/build.gradle ./
RUN --mount=type=cache,target=/home/gradle/.gradle \
	gradle dependencies --no-daemon --quiet || true

ARG CACHE_BUST=1
RUN echo "Cache bust: $CACHE_BUST"
COPY ErpAdapterService/src ./src
RUN --mount=type=cache,target=/home/gradle/.gradle \
		for i in 1 2 3 4 5; do \
			gradle bootJar --no-daemon -x test && break; \
			echo "bootJar attempt $i failed, retrying in 20s..."; \
			sleep 20; \
			if [ "$i" = "5" ]; then \
				echo "bootJar failed after 5 attempts"; \
				exit 1; \
			fi; \
		done

# ── Stage 2: Runtime ──────────────────────────────────────────────────────────
FROM eclipse-temurin:17-jre-alpine AS runtime
WORKDIR /app

RUN addgroup -S appgroup && adduser -S appuser -G appgroup

COPY --from=build /workspace/ErpAdapterService/build/libs/*.jar app.jar
RUN chown appuser:appgroup app.jar

# H2 file DB lives under /data (mounted as a named volume). Create it owned by the runtime
# user so a fresh volume inherits writable ownership — otherwise H2 fails with AccessDeniedException.
RUN mkdir -p /data && chown appuser:appgroup /data

USER appuser

EXPOSE 8088

ENTRYPOINT ["java", "-jar", "app.jar"]
