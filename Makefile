# Wrappers around docker compose. The host needs only Docker, git and make.
COMPOSE := docker compose
export HOST_UID := $(shell id -u)
export HOST_GID := $(shell id -g)
# Group of the Docker socket, so Testcontainers in the build container may use it.
export DOCKER_GID := $(shell stat -c %g /var/run/docker.sock 2>/dev/null || echo 0)

.PHONY: help up down build test gradle run status duckdb fetch-capture

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
