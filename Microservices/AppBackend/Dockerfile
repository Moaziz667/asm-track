# Build context is Microservices/, not AppBackend/ — the composite build resolves asm-tenant-core
# through a relative path, so both directories must be visible here.

FROM gradle:8.5-jdk17 AS build
WORKDIR /workspace

# The shared module first: it changes far less often than the service, so editing service code
# does not invalidate its build cache.
COPY asm-tenant-core asm-tenant-core
WORKDIR /workspace/AppBackend

COPY AppBackend/settings.gradle AppBackend/build.gradle ./
RUN gradle dependencies --no-daemon --quiet || true

COPY AppBackend/src ./src
RUN gradle bootJar --no-daemon -x test

FROM eclipse-temurin:17-jre-alpine AS runtime
WORKDIR /app

RUN addgroup -S appgroup && adduser -S appuser -G appgroup

COPY --from=build /workspace/AppBackend/build/libs/*.jar app.jar
RUN chown appuser:appgroup app.jar

USER appuser

EXPOSE 8080

ENTRYPOINT ["java", "-jar", "app.jar"]
