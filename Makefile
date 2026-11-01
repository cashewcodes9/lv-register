# Wrappers around docker compose. The host needs only Docker, git and make.
COMPOSE := docker compose
export HOST_UID := $(shell id -u)
export HOST_GID := $(shell id -g)
# Group of the Docker socket, so Testcontainers in the build container may use it.
export DOCKER_GID := $(shell stat -c %g /var/run/docker.sock 2>/dev/null || echo 0)
# Commands of the orchestration image, run in the dagster-code container.
DAGSTER_CMD := $(COMPOSE) --profile core --profile orchestration exec -T dagster-code python -m lvregister_orchestration.commands

.PHONY: help up down cli-image api build test gradle run dbt-build dbt-fixtures dbt-docs dagster capture-now capture-off capture-on dagster-test status duckdb fetch-capture

## Show the targets
help:
	@awk '/^## /{d=substr($$0,4)} /^[a-z-]+:/{if(d){split($$1,t,":"); printf "  %-14s %s\n", t[1], d; d=""}}' Makefile

## Start the long-running stack (profiles core and orchestration) and wait until healthy
up: cli-image
	$(COMPOSE) --profile core --profile orchestration up -d --wait

## Stop all services (data volumes are kept)
down:
	$(COMPOSE) --profile core --profile orchestration --profile tools down

## Build the cli image that Dagster starts for the Java steps, if it is missing
cli-image:
	@docker image inspect lv-register/cli:dev >/dev/null 2>&1 || $(COMPOSE) --profile core --profile tools build cli

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

## Rebuild and restart the read API; Swagger UI on http://localhost:8080/api/docs
api:
	$(COMPOSE) --profile core up -d --build --wait api

## Rebuild the cli and orchestration images and (re)start Dagster; UI on http://localhost:3000
dagster:
	$(COMPOSE) --profile core --profile tools build cli
	$(COMPOSE) --profile core --profile orchestration up -d --build --wait
	$(DAGSTER_CMD) sync-partitions
	@echo "Dagster UI: http://localhost:3000"

## Queue a capture now (today's Riga date, or DATE=YYYY-MM-DD for a past capture from the lake)
capture-now:
	$(DAGSTER_CMD) capture-now $(if $(DATE),--date $(DATE))

## Stop the weekly capture schedule (the stack keeps running)
capture-off:
	$(COMPOSE) --profile core --profile orchestration exec -T dagster-daemon dagster schedule stop weekly_capture -w /opt/dagster/home/workspace.yaml

## Start the weekly capture schedule again (a gap is not filled: the next diff spans it)
capture-on:
	$(COMPOSE) --profile core --profile orchestration exec -T dagster-daemon dagster schedule start weekly_capture -w /opt/dagster/home/workspace.yaml

## pytest of the Dagster definitions, in the orchestration image (its baked dbt manifest)
dagster-test:
	$(COMPOSE) --profile core --profile tools build dbt
	docker run --rm -w /work/orchestration \
	  -v $(CURDIR)/modules/quality/src/main/java/lvregister/quality/Checks.java:/work/contract/Checks.java:ro \
	  lv-register/orchestration:dev python -m pytest -q -p no:cacheprovider tests

## Service state, schedule state, captures, last runs, bucket usage and database sizes
status:
	$(COMPOSE) --profile core --profile orchestration ps -a
	@$(DAGSTER_CMD) status 2>/dev/null || echo "dagster is not running"
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
