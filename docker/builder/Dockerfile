# Build container: JDK 25 for the Gradle wrapper. Runs as the host user so files written
# to the bind-mounted repo are owned by that user. The Gradle cache is a named volume.
FROM eclipse-temurin:25.0.5_8-jdk-noble

ARG HOST_UID=1000
ARG HOST_GID=1000

# The base image ships an `ubuntu` user with uid 1000; remove it so the host ids are free.
RUN userdel --remove ubuntu 2>/dev/null || true \
 && (getent group "${HOST_GID}" || groupadd --gid "${HOST_GID}" builder) \
 && useradd --create-home --uid "${HOST_UID}" --gid "${HOST_GID}" builder \
 && mkdir -p /home/builder/.gradle /work \
 && chown "${HOST_UID}:${HOST_GID}" /home/builder/.gradle /work

USER builder
ENV GRADLE_USER_HOME=/home/builder/.gradle
WORKDIR /work
ENTRYPOINT ["./gradlew"]
CMD ["build"]
