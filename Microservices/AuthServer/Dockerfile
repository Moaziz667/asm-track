FROM gradle:8.5-jdk17 AS build
WORKDIR /workspace
ENV GRADLE_OPTS="-Dorg.gradle.internal.http.connectionTimeout=120000 -Dorg.gradle.internal.http.socketTimeout=120000"
COPY settings.gradle build.gradle ./
RUN --mount=type=cache,target=/home/gradle/.gradle \
    gradle dependencies --no-daemon --quiet || true
ARG CACHE_BUST=1
RUN echo "Cache bust: $CACHE_BUST"
COPY src ./src
RUN --mount=type=cache,target=/home/gradle/.gradle \
    gradle bootJar --no-daemon -x test

FROM eclipse-temurin:17-jre-alpine AS runtime
WORKDIR /app
RUN addgroup -S appgroup && adduser -S appuser -G appgroup
COPY --from=build /workspace/build/libs/*.jar app.jar
RUN chown appuser:appgroup app.jar
USER appuser
EXPOSE 8089
ENTRYPOINT ["java", "-jar", "app.jar"]
