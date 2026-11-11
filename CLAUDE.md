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
- If you are unsure about a topic, a situation or a decision, ask me with a multiple-choice
  question (the AskUserQuestion tool): give 2-4 concrete options with their trade-offs,
  put your recommendation first, and wait for my answer before continuing.
- Start with Milestone 1. Show me the source assessment before writing domain code.
- Before writing domain code, download one capture of every file, record the real column
  names and value sets in `docs/source-assessment.md`, and only then finalize the model.
  Do not guess column names.
- Run every build, test and tool in a container, never on the host. If a tool is missing,
  add it to an image or a compose service. The host has only Docker, git and make.
- Keep [EXECUTION.md](EXECUTION.md) current so work can resume in a new session: what is
  done, what is on disk but uncommitted, next steps in order, pinned versions, environment
  facts and decisions. Update it, and the PLAN.md checkboxes, at the end of every work
  session, before a restart or when asked to pause, and after every milestone. Read it
  first when starting a session.
- Branches: work from `dev`. Each milestone gets its own branch from `dev`, named
  `milestone/<n>-<short-name>` (e.g. `milestone/3-storage-ingest`). Commit locally in small
  steps. When the milestone builds green (`make build`), push the branch and open a PR into
  `dev`; once CI on the PR is green, merge it (merge commit) and summarize it to me. Never
  commit directly to `main`; `dev` goes to `main` only when I ask, through a PR.
- After each milestone: run `make build` (plus `make dbt-build` from Milestone 6 and the
  frontend build from Milestone 9 on), summarize what changed, and tick the checkboxes in
  PLAN.md.
- Document everything in Markdown as you go: docs/architecture.md for how it works,
  EXECUTION.md for state and decisions, README for how to run it. After every milestone, add
  FAQ entries to [docs/FAQ.md](docs/FAQ.md) for the questions and decisions of that milestone
  (why this library, what was measured, what was rejected), linked from PLAN.md and
  docs/architecture.md (owner, 2026-11-01).
- Small commits with clear messages.
- Never commit raw data, lake files, marts or person-level data.
- Never add Spring dependencies outside `modules/api/api`.
- Keep domain logic in Java. Python in `orchestration/` only wires and checks; dbt models
  stay SQL over the lake.
- If the real data contradicts this plan, tell me and propose a change. Do not silently work
  around it.
- Prefer simple code over clever code. If a library choice is unclear, ask me.

## Decisions already made

Do not reopen these without asking:
- Java 25, Gradle Kotlin DSL, Picocli; plain Java in the pipeline.
- Spring Boot 4 (not 3.x), only in `modules/api/api`.
- Dagster + dbt stay; Python only for orchestration.
- SeaweedFS (S3 API) for raw files, lake and marts; DuckDB over Parquet. The architecture
  follows [Oversee](https://github.com/cashewcodes9/Oversee).
- Serving: PostgreSQL 18 (one instance, databases `lv_serving` and `dagster`) behind the
  `serving` port (`ServingWriter`, `ServingQueries`), plain JDBC + HikariCP, Flyway. Code
  outside `serving-*` adapters never touches SQL or a search client, so an Elasticsearch
  adapter can replace Postgres later (ROADMAP.md section 4). Decided 2026-10-31, replacing
  Elasticsearch.
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
