# syntax=docker/dockerfile:1
# Multi-stage build for the Java images (PLAN.md section 6.1).
# Stages: jdk (Gradle build) -> cli. The `web` and `api` stages join in Milestones 8 and 9.

FROM eclipse-temurin:25.0.5_8-jdk-noble AS jdk
WORKDIR /src
COPY gradlew settings.gradle.kts build.gradle.kts gradle.properties ./
COPY gradle/ gradle/
COPY buildSrc/ buildSrc/
COPY modules/ modules/
RUN --mount=type=cache,target=/root/.gradle \
    ./gradlew --no-daemon :cli:installDist

FROM eclipse-temurin:25.0.5_8-jre-noble AS cli
# Non-root runtime user (the image's `ubuntu` user has uid 1000; use a separate one).
RUN useradd --system --uid 10001 --no-create-home lvregister
COPY --from=jdk /src/modules/cli/build/install/lv-register /opt/lv-register
USER lvregister
WORKDIR /tmp
ENTRYPOINT ["/opt/lv-register/bin/lv-register"]
CMD ["--help"]
