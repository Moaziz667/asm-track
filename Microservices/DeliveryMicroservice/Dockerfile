# ── Stage 1: Build ────────────────────────────────────────────────────────────
FROM gradle:8.5-jdk17 AS build
WORKDIR /workspace

# Increase Gradle HTTP timeouts to better tolerate transient network slowness.
ENV GRADLE_OPTS="-Dorg.gradle.internal.http.connectionTimeout=120000 -Dorg.gradle.internal.http.socketTimeout=120000"

COPY settings.gradle build.gradle ./
# Cache dependency resolution layer
RUN --mount=type=cache,target=/home/gradle/.gradle \
	gradle dependencies --no-daemon --quiet || true

# ARG must be USED in a RUN to bust subsequent COPY cache
ARG CACHE_BUST=1
RUN echo "Cache bust: $CACHE_BUST"
COPY src ./src
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

COPY --from=build /workspace/build/libs/*.jar app.jar
RUN chown appuser:appgroup app.jar

USER appuser

EXPOSE 8082

ENTRYPOINT ["java", "-jar", "app.jar"]
