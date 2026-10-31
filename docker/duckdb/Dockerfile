# Small image with the DuckDB CLI plus curl and jq, for exploring the UR files
# and ad-hoc SQL over the lake. Nothing is installed on the host.
FROM debian:bookworm-20261027-slim

# 1.4.x LTS: DuckDB 2.0 binaries need AVX, which the dev VM CPU lacks (see source assessment).
ARG DUCKDB_VERSION=1.4.5
ARG TARGETARCH=amd64

RUN apt-get update \
 && apt-get install -y --no-install-recommends ca-certificates curl jq unzip file \
 && rm -rf /var/lib/apt/lists/* \
 && curl -fsSL -o /tmp/duckdb.zip \
      "https://github.com/duckdb/duckdb/releases/download/v${DUCKDB_VERSION}/duckdb_cli-linux-${TARGETARCH}.zip" \
 && unzip -q /tmp/duckdb.zip -d /usr/local/bin \
 && rm /tmp/duckdb.zip \
 && chmod +x /usr/local/bin/duckdb \
 && useradd --create-home --uid 1000 explorer

USER explorer
WORKDIR /work
# Install extensions at build time so sessions work offline.
RUN duckdb -c "INSTALL httpfs;"

ENTRYPOINT ["duckdb"]
