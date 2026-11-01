# Wrappers around docker compose. The host needs only Docker, git and make.
COMPOSE := docker compose
export HOST_UID := $(shell id -u)
export HOST_GID := $(shell id -g)
# Group of the Docker socket, so Testcontainers in the build container may use it.
export DOCKER_GID := $(shell stat -c %g /var/run/docker.sock 2>/dev/null || echo 0)

.PHONY: help up down build test gradle run dbt-build dbt-fixtures dbt-docs status duckdb fetch-capture

## Show the targets
help:
	@awk '/^## /{d=substr($$0,4)} /^[a-z-]+:/{if(d){split($$1,t,":"); printf "  %-14s %s\n", t[1], d; d=""}}' Makefile

## Start the long-running stack (profile core: lake, postgres) and wait until healthy
up:
	$(COMPOSE) --profile core up -d --wait

## Stop all services (data volumes are kept)
down:
	$(COMPOSE) --profile core --profile tools down

## Gradle build with all tests, in the build container
build:
	$(COMPOSE) run --rm build build

## Gradle tests only, in the build container
test:
	$(COMPOSE) run --rm build test

## Any Gradle tasks in the build container: make gradle ARGS=":storage:test"
gradle:
	$(COMPOSE) run --rm build $(ARGS)

## Run all Java steps for one capture: make run DATE=2026-11-02
run:
	@test -n "$(DATE)" || { echo "usage: make run DATE=YYYY-MM-DD"; exit 2; }
	$(COMPOSE) --profile core run --rm --build cli run --date $(DATE)

## dbt seed, run and test over the real lake; marts to s3://lv-marts
dbt-build:
	$(COMPOSE) --profile core run --rm dbt build

## dbt build on the committed fixtures (transform/fixtures/lake), no S3 needed; used by CI
dbt-fixtures:
	$(COMPOSE) --profile core run --rm --no-deps -e DBT_TARGET=fixtures -e LV_LAKE_ROOT=/work/transform/fixtures/lake \
	  dbt build --vars '{mass_address_min_companies: 1}'

## dbt docs with the lineage graph on http://localhost:8081
dbt-docs:
	$(COMPOSE) --profile core run --rm -p 127.0.0.1:8081:8081 --entrypoint sh dbt \
	  -c "dbt docs generate && dbt docs serve --host 0.0.0.0 --port 8081 --no-browser"

## Service state, bucket usage and database sizes
status:
	$(COMPOSE) --profile core --profile tools ps -a
	@echo "s3.bucket.list" | $(COMPOSE) exec -T lake weed shell -master=lake:9333 2>/dev/null || echo "lake is not running"
	@$(COMPOSE) exec -T postgres psql -U postgres -Atc "SELECT datname || ': ' || pg_size_pretty(pg_database_size(datname)) FROM pg_database WHERE datname IN ('lv_serving','dagster')" 2>/dev/null || echo "postgres is not running"

## Open the DuckDB CLI on ./data
duckdb:
	mkdir -p data
	$(COMPOSE) run --rm duckdb

## Milestone 1: download one capture of every UR CSV into data/raw/date=<today>
fetch-capture:
	mkdir -p data
	$(COMPOSE) run --rm --entrypoint bash duckdb scripts/fetch-capture.sh $(DATE)
