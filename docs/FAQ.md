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
- [Which Dagster version, and how does the Java CLI talk to Dagster?](#which-dagster-version-and-how-does-the-java-cli-talk-to-dagster)
- [Does Dagster fit into the VM next to a capture?](#does-dagster-fit-into-the-vm-next-to-a-capture)
- [How is the API built, and why these choices?](#how-is-the-api-built-and-why-these-choices)
- [How does the company network work, and is it a privacy problem?](#how-does-the-company-network-work-and-is-it-a-privacy-problem)
- [Why was search slow on common words, and what fixed it?](#why-was-search-slow-on-common-words-and-what-fixed-it)
- [Why shadcn/ui and not Semantic UI, which North Data uses?](#why-shadcnui-and-not-semantic-ui-which-north-data-uses)
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

## Which Dagster version, and how does the Java CLI talk to Dagster?

**Version** (decided 2026-11-01): Dagster 1.13.28 with its integrations at 0.29.28
(dagster-webserver, -postgres, -docker, -dbt, -pipes). They are released together, and they
install cleanly next to dbt-core 1.12.6, dbt-duckdb 1.11.1 and duckdb 1.4.5 (`pip check`).
dagster-dbt accepts `dbt-core >=1.7,<1.13`.

**Talking to Dagster: Dagster Pipes.** Dagster starts the CLI in the `cli` image
(`PipesDockerClient`) and passes two environment variables, `DAGSTER_PIPES_CONTEXT` and
`DAGSTER_PIPES_MESSAGES`, each base64 of zlib of JSON. The CLI writes one JSON line per
message to stderr (`opened`, `report_asset_materialization`, `report_asset_check`, `closed`),
marked with `"__dagster_pipes_version": "0.1"`; Dagster reads the container logs and ignores
every other line, so our JSON logs on stdout do not interfere.

Three options were weighed:

| Option | For | Against |
|---|---|---|
| **Own implementation in the `pipes` module** (chosen) | about 200 lines on the JDK and Jackson 3, which the project uses anyway; easy to test against values written by the Python library | we maintain it (the protocol is small and versioned) |
| Official `io.dagster:pipes` 1.1.1 | exists on Maven Central | repository archived and labelled "prototype"; pulls in Jackson 2, the AWS S3 SDK and its own Picocli |
| No Pipes | Java unchanged | Python would read `report.json` from S3 itself: checks and metadata less direct |

The checks are computed in Java (`lvregister.quality.Checks`); the Dagster code only declares
their names, and a pytest makes sure the two lists match. See
[architecture.md, Java through Pipes](architecture.md#java-through-pipes).

## Does Dagster fit into the VM next to a capture?

Yes, because nothing runs in parallel. Measured on the VM (1 CPU, 3.8 GB RAM, 4 GB swap):

| What | Memory |
|---|---|
| `lake` (SeaweedFS) | 0.2–0.36 GB |
| `postgres` | 0.03 GB idle, up to 0.24 GB while indexing |
| Dagster idle: webserver 130 MB, daemon 104 MB, code server 187 MB | 0.42 GB |
| heaviest step: cli `diff` or `index` (two snapshots in the heap, `-Xmx2g`) | 2.2 GB |
| dbt build (DuckDB) | 1.0 GB |

The first full capture through Dagster (2026-11-01, about 25 minutes) used at most 2.96 GB of
the 3.8 GB, in the `diff` step. Three guardrails keep it
that way:

1. **One run at a time:** `QueuedRunCoordinator` with `max_concurrent_runs: 1`.
2. **One step at a time:** the in-process executor runs the steps of a run one after the other.
3. **No heavy builds during a capture:** `make build` (Gradle, about 1.5 GB) should not run on
   Monday morning at 06:00 Riga time.

If memory gets tight, the lever is the `diff` step (two full snapshots), not Dagster.

## How is the API built, and why these choices?

Milestone 8, decided 2026-11-01. Spring Boot 4.1.2 (the latest GA; 4.2 was only a release
candidate), only in `modules/api`. The pipeline stays plain Java.

| Choice | Why |
|---|---|
| Reads only through the `serving` port | the API does not know it is PostgreSQL; an Elasticsearch adapter can replace it (ArchUnit enforces it, and that the API never writes) |
| DTO records separate from the serving views | the JSON uses `null` instead of `Optional`, and stored JSON (payloads, quality reports) is embedded as JSON, which makes clean OpenAPI types for the frontend |
| One version per library, ours win | Boot 4.1.2 pins older Jackson (3.1.7), Flyway, HikariCP and JUnit; the BOM is imported as a plain Gradle platform, so the newer catalog versions (Jackson 3.2.4 …) are used everywhere. The Testcontainer test would catch an incompatibility |
| ProblemDetail errors | the RFC standard error body (`application/problem+json`) for 400, 404 and 429 |
| Rate limit 120/min, burst 30 per IP | enough for the frontend (a page loads a few endpoints), stops scraping 490,000 companies |
| Caffeine cache, 10 minutes | stats and search only change when a capture is indexed, once a week |
| The API never migrates the schema | the index step owns it; the API is read-only |

See [architecture.md, Serving](architecture.md#serving).

## How does the company network work, and is it a privacy problem?

`GET /api/companies/{reg}/network` returns a graph two hops deep (owner's choice): the company,
its officers, owners and beneficial owners, the other companies of those people and entities,
and the people of those companies. It is capped at 30 other companies and 300 nodes; the
response says when it was cut. The busiest case on the real register (the person with the
most roles) answers in 0.05 s.

The link "this person also holds a role there" comes from the person key (a hash of the
normalized name and the masked personal code). PostgreSQL keeps a `party_roles` table
(671,912 links, 388,644 people and entities), filled from the company documents by a trigger,
so the Java writer did not change.

Privacy: PLAN.md section 12 allows persons only in the context of one company, its page and
its network graph, and never in bulk. The network starts from one company, is capped, and is
rate-limited; there is no endpoint that lists or exports persons. The data itself is the open
register data UR publishes.

## Why was search slow on common words, and what fixed it?

Measured on the real register: a search for "nams" took 6 s the first time. Two causes:

1. **Disk.** The trigram search ran on the `companies` table, whose rows carry every company
   document (501 MB). A common word touched about 8,600 random blocks, which the 128 MB of
   PostgreSQL buffers do not keep. Fix: a narrow `company_search` table (name, unaccented
   name, legal form, status; about 60 MB) with the trigram index, kept by a trigger.
2. **CPU.** "sabiedriba" (Latvian for "company") appears in 193,000 names. Ranking every
   match by trigram similarity costs seconds on one CPU. Fix: a query with more than 1,000
   substring matches is "broad": it returns the substring matches ranked exact name, prefix,
   then shorter names, and computes similarity only for the page it returns.

Now typical searches take 0.03–0.4 s, the commonest word about 2.8 s uncached, and results are
cached for 10 minutes. The frontend waits for a pause in typing before it searches.

## Why shadcn/ui and not Semantic UI, which North Data uses?

North Data's frontend uses TypeScript, Semantic UI and d3.js. Checked on 2026-11-01:
`semantic-ui-css` had no release since October 2022, and `semantic-ui-react` (last release
December 2023) supports React only up to 18. The owner's rule: no unmaintained libraries.
Fomantic UI, the maintained fork, was an option; the owner chose **shadcn/ui** after comparing
the well-maintained libraries (MUI, Ant Design, Mantine, Chakra, Blueprint, shadcn/ui, Radix,
React Aria, HeroUI and others; all released within months).

shadcn/ui is not a dependency in the usual sense: its components (built on Radix primitives
and Tailwind CSS) are copied into `web/src/components/ui`, so the repo owns every line, and only
what is used is shipped. Its charts are built on Recharts, used for the Overview page. The
company network is drawn with **d3.js**, as at North Data. TypeScript stays on 5.9:
TypeScript 7.0 is out, but `openapi-typescript`, which generates the API types, accepts only 5.x.

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
