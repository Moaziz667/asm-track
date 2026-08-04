# Build context is Microservices/, not DriverService/ — the composite build resolves
# asm-tenant-core through a relative path, so both directories must be visible here.
FROM gradle:8.5-jdk17 AS build
WORKDIR /workspace

# The shared module first: it changes far less often than the service, so keeping it in its own
# layer means editing service code does not invalidate its build cache.
COPY asm-tenant-core asm-tenant-core

WORKDIR /workspace/DriverService
COPY DriverService/build.gradle DriverService/settings.gradle ./
COPY DriverService/src src
RUN gradle bootJar --no-daemon

FROM eclipse-temurin:17-jre-alpine
WORKDIR /app
COPY --from=build /workspace/DriverService/build/libs/*.jar app.jar
EXPOSE 8086
ENTRYPOINT ["java", "-jar", "app.jar"]
