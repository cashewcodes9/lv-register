# Wrappers around docker compose. The host needs only Docker and git.
COMPOSE := docker compose
export HOST_UID := $(shell id -u)
export HOST_GID := $(shell id -g)

.PHONY: duckdb fetch-capture

## Open the DuckDB CLI on ./data
duckdb:
	$(COMPOSE) run --rm duckdb

## Milestone 1: download one capture of every UR CSV into data/raw/date=<today>
fetch-capture:
	mkdir -p data
	$(COMPOSE) run --rm --entrypoint bash duckdb scripts/fetch-capture.sh $(DATE)
