#!/bin/sh
# Runs once, on the first start with an empty data volume (docker-entrypoint-initdb.d).
# One PostgreSQL instance, two databases with their own owners:
#   lv_serving  serving store for the API (schema via Flyway in serving-postgres)
#   dagster     Dagster run, event log and schedule storage (Milestone 7)
set -eu
psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname postgres \
     -v serving_pw="$SERVING_DB_PASSWORD" -v dagster_pw="$DAGSTER_DB_PASSWORD" <<'SQL'
CREATE ROLE lv_serving LOGIN PASSWORD :'serving_pw';
CREATE DATABASE lv_serving OWNER lv_serving;
CREATE ROLE dagster LOGIN PASSWORD :'dagster_pw';
CREATE DATABASE dagster OWNER dagster;
REVOKE CONNECT ON DATABASE lv_serving FROM PUBLIC;
REVOKE CONNECT ON DATABASE dagster FROM PUBLIC;
SQL
