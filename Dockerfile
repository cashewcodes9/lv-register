# syntax=docker/dockerfile:1
# Multi-stage build for the Java images (PLAN.md section 6.1).
# Stages: web (React build) and jdk (Gradle build) -> cli and api (API + web/dist on one port).

# The React app (Milestone 9): installed from the lock file and built with the pinned Node LTS.
FROM node:24.22.2-alpine AS web
WORKDIR /web
COPY web/package.json web/package-lock.json web/.npmrc ./
RUN --mount=type=cache,target=/root/.npm npm ci --no-audit --no-fund
COPY web/ ./
RUN npm run build

FROM eclipse-temurin:25.0.5_8-jdk-noble AS jdk
WORKDIR /src
COPY gradlew settings.gradle.kts build.gradle.kts gradle.properties ./
COPY gradle/ gradle/
COPY buildSrc/ buildSrc/
COPY modules/ modules/
RUN --mount=type=cache,target=/root/.gradle \
    ./gradlew --no-daemon :cli:installDist

# DuckDB's httpfs extension, pre-installed so the lake step needs no extension download at
# run time. Must match the DuckDB JDBC version (1.4.5).
FROM eclipse-temurin:25.0.5_8-jdk-noble AS duckdb-extensions
ARG DUCKDB_VERSION=1.4.5
ADD --checksum=sha256:6743c9819c6fb6c084ebaf78047346db5172a2da3154b0aee7d06eaf59c18c91 \
    https://extensions.duckdb.org/v${DUCKDB_VERSION}/linux_amd64/httpfs.duckdb_extension.gz /tmp/httpfs.gz
RUN mkdir -p /opt/duckdb/extensions/v${DUCKDB_VERSION}/linux_amd64 \
 && gunzip -c /tmp/httpfs.gz > /opt/duckdb/extensions/v${DUCKDB_VERSION}/linux_amd64/httpfs.duckdb_extension

FROM eclipse-temurin:25.0.5_8-jre-noble AS cli
# Non-root runtime user (the image's `ubuntu` user has uid 1000; use a separate one).
RUN useradd --system --uid 10001 --no-create-home lvregister
COPY --from=jdk /src/modules/cli/build/install/lv-register /opt/lv-register
COPY --from=duckdb-extensions /opt/duckdb /opt/duckdb
ENV DUCKDB_EXTENSION_DIRECTORY=/opt/duckdb/extensions \
    DUCKDB_TEMP_DIRECTORY=/tmp/duckdb \
    LV_WORK_DIR=/tmp/lv-register
USER lvregister
WORKDIR /tmp
ENTRYPOINT ["/opt/lv-register/bin/lv-register"]
CMD ["--help"]

# The api jar with the frontend in classpath:/static. Only this stage depends on `web`, so a
# frontend change does not rebuild the cli image.
FROM jdk AS api-jar
COPY --from=web /web/dist /web-dist
ENV LV_WEB_DIST=/web-dist
RUN --mount=type=cache,target=/root/.gradle \
    ./gradlew --no-daemon :api:bootJar

# The read API (Spring Boot) with the frontend. Production target.
FROM eclipse-temurin:25.0.5_8-jre-noble AS api
RUN useradd --system --uid 10001 --no-create-home lvregister
COPY --from=api-jar /src/modules/api/build/libs/api-0.1.0-SNAPSHOT.jar /opt/lv-register/api.jar
USER lvregister
WORKDIR /tmp
EXPOSE 8080
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /opt/lv-register/api.jar"]
