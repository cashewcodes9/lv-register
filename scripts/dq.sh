#!/usr/bin/env bash
# Run SQL against the capture in the duckdb container: scripts/dq.sh "<sql>"
cd "$(dirname "$0")/.."
HOST_UID=$(id -u) HOST_GID=$(id -g) docker compose run --rm -T duckdb -markdown -c "SET VARIABLE d='data/raw/date=2026-10-31'; CREATE MACRO csv(f) AS TABLE SELECT * FROM read_csv(getvariable('d')||'/'||f, delim=';', header=true, sample_size=-1, hive_partitioning=false, all_varchar=true); $1" 2>&1 | grep -v -E '^ ?(Container|Network)'
