# FAQ: why the project is built this way

Short answers to questions that came up while building lv-register, with links to where each
decision is written down. The plan is [PLAN.md](../PLAN.md), the design is
[architecture.md](architecture.md), and the decision log is in
[EXECUTION.md](../EXECUTION.md#decisions-log).

- [What does each part of the stack do?](#what-does-each-part-of-the-stack-do)
- [Why PostgreSQL for serving, and not Elasticsearch?](#why-postgresql-for-serving-and-not-elasticsearch)
- [What does dbt add?](#what-does-dbt-add)
- [What does Dagster add?](#what-does-dagster-add)
- [Are dbt and Dagster worth their cost here?](#are-dbt-and-dagster-worth-their-cost-here)
- [What are marts?](#what-are-marts)
- [Why SeaweedFS, and why the AWS SDK if nothing runs on AWS?](#why-seaweedfs-and-why-the-aws-sdk-if-nothing-runs-on-aws)
- [Why weekly captures, and how are captures dated?](#why-weekly-captures-and-how-are-captures-dated)

## What does each part of the stack do?

```
UR files (data.gov.lv)
   │  download (Java)         raw CSV + sha256 manifest      → s3://lv-raw    (SeaweedFS)
   │  lake (Java + DuckDB)    every row as Parquet            → s3://lv-lake
   │  diff (Java)             snapshot vs previous → events   → s3://lv-lake/events
   │  quality (Java)          run record + quality report     → s3://lv-lake/reports
   │  dbt (SQL over the lake) staging → marts + data tests    → s3://lv-marts
   │  index (Java)            companies, events, runs, marts  → PostgreSQL (serving store)
   ▼
Spring Boot API → React frontend
Dagster: schedules all of the above weekly, checks it, keeps the history
```

Java owns the domain logic (normalization, diff); dbt owns the analysis in SQL; Dagster
only orchestrates. See [PLAN.md section 4](../PLAN.md#4-tech-stack) and
[architecture.md, Layers](architecture.md#layers).

## Why PostgreSQL for serving, and not Elasticsearch?

The serving store answers the API's many small requests: company search, a company page
with officers and owners, event timelines, the changes feed, and the pipeline runs. The lake
(Parquet on SeaweedFS) is built for batch work, not for that.

The plan first chose Elasticsearch, mainly for company-name search. It was replaced on
2026-10-31:

- **RAM:** Elasticsearch needs about 1 GB on a 4 GB VM; PostgreSQL uses about 240 MB with the
  whole register loaded.
- **Search is good enough:** `pg_trgm` + `unaccent` ignore case and Latvian diacritics and
  tolerate small typos. "rigas udens" finds `SIA "Rīgas ūdens"` first.
- **One database:** the same PostgreSQL instance also holds Dagster's run storage
  (database `dagster`).

Serving sits behind a port (`ServingWriter`, `ServingQueries`) with a contract test suite.
An Elasticsearch adapter can replace PostgreSQL later without touching the pipeline or the
API: it only has to pass the same tests. ArchUnit makes sure only the wiring knows which
adapter is used. See [architecture.md, Serving](architecture.md#serving) and
[ROADMAP.md section 4](../ROADMAP.md#4-elasticsearch-serving-adapter).

## What does dbt add?

dbt runs SQL models over the Parquet lake (through DuckDB) and writes the results as Parquet
marts to `s3://lv-marts`.

1. **The analysis layer.** Counts per legal form, status and region, events per capture, and
   the value feature (mass-registration addresses) are SQL questions across all captures.
   In dbt each is a short, readable model instead of hand-written aggregation code in Java.
2. **Data tests as an independent check.** Declarative tests (unique keys, no orphan roles,
   allowed status values) run on every build. The strongest one is the **parity test**: dbt
   computes the same diff in SQL, and a test requires it to match the Java events, so a bug
   in either version shows up as a mismatch. The manual SQL check of the first real diff
   ([source assessment section 10](source-assessment.md#10-first-real-diff-milestone-4)) was
   exactly this test, done by hand.
3. **Lineage and docs.** `make dbt-docs` draws the graph from the lake files to the marts and
   on to the API and frontend (exposures).

Rule: "Keep domain logic in Java; dbt models stay SQL over the lake" ([CLAUDE.md](../CLAUDE.md)).
See [architecture.md, Transformation with dbt](architecture.md#transformation-with-dbt)
and [PLAN.md Milestone 6](../PLAN.md#milestone-6-dbt).

## What does Dagster add?

1. **The weekly schedule with an off/on switch** (`make capture-off`, `make capture-on`).
   Without it, someone has to run the five CLI steps by hand every Monday.
2. **One partition per capture date.** The asset graph shows the state of every capture.
   Re-running a past capture, or catching up after a gap, is a backfill from the UI.
3. **Asset checks that can block.** The warnings of the quality report become checks. If the
   drop share spikes because UR changed its format, Dagster stops before `index`, so broken
   data never reaches the API. Without Dagster, a WARN report stops nothing.
4. **Retries, failure alerts, run history,** and the `prune` retention step at the end of
   each run.
5. **No new logic.** Dagster runs the same `cli` image as a person would, and the CLI reports
   materializations and check results back through Dagster Pipes.

See [architecture.md, Orchestration with Dagster](architecture.md#orchestration-with-dagster)
and [PLAN.md Milestone 7](../PLAN.md#milestone-7-dagster-orchestration).

## Are dbt and Dagster worth their cost here?

- **Cost:** about three extra containers, roughly 0.5–1 GB of RAM, and a Python image to
  maintain.
- **A lighter alternative exists:** a cron job running `make run DATE=…` covers the schedule,
  and the quality report already exists. For a private tool that would be enough.
- **Why they stay:** the project shows a production-style data platform
  ([PLAN.md section 1](../PLAN.md#1-why-latvia)): a typed Java core, SQL models with data
  tests, and an orchestrator with partitions, checks and backfills. dbt also delivers what
  the frontend needs (stats and feature marts) and the Java/SQL parity test. Dagster is kept
  lean: no extra services beyond the plan, and its storage in the existing PostgreSQL.

## What are marts?

A **mart** is a finished table built for one question or screen, at the end of the pipeline.
It is read directly, without further joins or calculations. The term comes from data
warehousing ("data mart": a small, focused slice of the warehouse).

```
raw CSV (lv-raw)      as UR publishes it
   ↓
lake (lv-lake)        same rows as Parquet, all text, one copy per capture
   ↓
dbt staging           typed and cleaned, one model per file      ("what the data is")
   ↓
dbt intermediate      joins and logic (company + status + capital)
   ↓
marts (lv-marts)      final tables per use                       ("what we want to know")
```

| Mart | One row per | Used by |
|---|---|---|
| `dim_companies` | company (latest state) | stats, cross-checks |
| `fct_company_events` | change event | changes feed, events per capture |
| stats aggregates | legal form × status × region; events per type per capture | overview page (`GET /api/stats`) |
| `feat_mass_registration_addresses` | address with many companies | insights page (`GET /api/features/mass-addresses`) |

`dim_` (dimension) tables describe things, `fct_` (fact) tables record things that happened.
Marts live in `s3://lv-marts` for analysis and tests; the index step copies the small ones
the frontend needs into PostgreSQL, so the API never scans 490,000 companies per request. See
[PLAN.md section 10](../PLAN.md#10-value-features-pick-1-for-mvp-more-if-time).

## Why SeaweedFS, and why the AWS SDK if nothing runs on AWS?

SeaweedFS is a local, S3-compatible object store (the `lake` service), as in Oversee. Raw
files, the lake and the marts are plain objects, and DuckDB reads them over the S3 API.

The Java code uses the AWS SDK v2 only as the **S3 protocol client**. It always talks to the
endpoint in `S3_ENDPOINT` (required), path style, and nothing talks to Amazon. Another
S3-compatible store is a configuration change. See
[architecture.md, Layers](architecture.md#layers) and
[PLAN.md section 4](../PLAN.md#4-tech-stack).

## Why weekly captures, and how are captures dated?

UR publishes only the current state, every evening around 21:25 UTC. The lake is the only
history this project has. A demo does not need daily snapshots, so it captures **once a
week**, Monday 06:00 Europe/Riga, with bounded retention
([PLAN.md section 6.2](../PLAN.md#62-capture-schedule-retention-and-onoff)). A change that is
undone within one week is not visible.

A capture's date is its **Riga date**. A capture taken in the evening after publication gets
the next date only after 22:00 UTC (midnight in Riga in winter). The diff always compares a
capture with the **previous capture in the lake**, so gaps are fine, and each event records
both dates. Daily captures are added only if a week turns out to give too little data.
