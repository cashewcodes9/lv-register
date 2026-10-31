# CLAUDE.md

Instructions for Claude Code in this repository. The full plan is in [PLAN.md](PLAN.md);
these rules come from it and from the planning sessions. If this file and PLAN.md disagree,
ask.

## Role

You are helping me build a portfolio project for the North Data "Modern Java Developer"
application (Hamburg): a pipeline that turns the open data of the Latvian Register of
Enterprises (UR) into clean company snapshots and change events, with a read-only API and a
React frontend. Follow [PLAN.md](PLAN.md) milestone by milestone.

## Working rules

- Ask me before changing scope.
- If anything is unclear, ask me instead of guessing.
- Start with Milestone 1. Show me the source assessment before writing domain code.
- Before writing domain code, download one capture of every file, record the real column
  names and value sets in `docs/source-assessment.md`, and only then finalize the model.
  Do not guess column names.
- Run every build, test and tool in a container, never on the host. If a tool is missing,
  add it to an image or a compose service. The host has only Docker and git.
- After each milestone: run `make build` (plus `make dbt-build` from Milestone 6 and the
  frontend build from Milestone 9 on), summarize what changed, and tick the checkboxes in
  PLAN.md.
- Small commits with clear messages.
- Never commit raw data, lake files, marts or person-level data.
- Never add Spring dependencies outside `modules/api`.
- Keep domain logic in Java. Python in `orchestration/` only wires and checks; dbt models
  stay SQL over the lake.
- If the real data contradicts this plan, tell me and propose a change. Do not silently work
  around it.
- Prefer simple code over clever code. If a library choice is unclear, ask me.

## Decisions already made

Do not reopen these without asking:
- Java 25, Gradle Kotlin DSL, Picocli; plain Java in the pipeline.
- Spring Boot 4 (not 3.x), only in `modules/api`.
- Dagster + dbt stay; Python only for orchestration.
- SeaweedFS (S3 API) for raw files, lake and marts; DuckDB over Parquet; Elasticsearch for
  serving. The architecture follows [Oversee](https://github.com/cashewcodes9/Oversee).
- Weekly captures (not daily), bounded retention, and an off/on switch (PLAN.md section 6.2).
- Observability beyond logs and Dagster checks is on [ROADMAP.md](ROADMAP.md), not in the MVP.
- No fixed deadline.

## Code style

- Java: immutable records, sealed interfaces, pattern matching for switch, record patterns,
  streams, Optional.
- Pure functions for all transformation logic. I/O only at the edges.
- No setters, no nulls in the domain model (use Optional), no inheritance hierarchies beyond
  sealed types.
- Python (Dagster) and SQL (dbt) stay thin: they orchestrate and check.

## Personal data

Officers and beneficial owners are personal data. Never put person data in logs, Dagster
metadata, commits or test fixtures taken from real files.
