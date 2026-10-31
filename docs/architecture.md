# Architecture

lv-register turns the daily open data of the Latvian Register of Enterprises (Uzņēmumu
reģistrs, UR) into typed company snapshots and a feed of change events, then serves them
through a read-only API and a React app. Dagster schedules and checks every step, dbt models
and tests the data in SQL, and Grafana shows logs, metrics, traces and alerts.

It follows the layer design of [Oversee](https://github.com/cashewcodes9/Oversee): raw cache,
S3 lake, cleaning with drop counts, dbt, Dagster assets. The domain logic is in Java 25.
Scope and milestones are in [PLAN.md](../PLAN.md). Column names and value sets come from
[source-assessment.md](source-assessment.md) (Milestone 1), not from this document.

## Layers

```
UR CSVs ─► s3://lv-raw ─► s3://lv-lake ─────► normalize ─► Snapshot ─► diff ─► s3://lv-lake/events
data.gov.lv  as published   Parquet per file    Java, pure    typed       Java    JSON Lines per day
             + manifest     and day, unfiltered               records       │
                                   │                                        │
                                   └────────────► dbt (DuckDB) ◄────────────┘
                                                  staging ─► intermediate ─► marts ─► s3://lv-marts
                                                                                        │
              quality ─► s3://lv-lake/reports ─────────────────────────────────┐        │
                                                                               ▼        ▼
                                                              Elasticsearch: companies, events,
                                                              runs, stats, features ─► API ─► React
```

| Layer | Where | Holds | Written by |
|---|---|---|---|
| Raw | `s3://lv-raw/date=YYYY-MM-DD/` | UR files as published, `manifest.json` (name, URL, sha256, size, fetched at) | `ingest` |
| Lake | `s3://lv-lake/<file>/date=YYYY-MM-DD/<file>.parquet` | every row and column, typed by DuckDB, source sha256 in key-value metadata | `lake` |
| Snapshot | in memory | `Snapshot` record: companies and roles of one day, keyed by registration number | `normalize` |
| Events | `s3://lv-lake/events/date=YYYY-MM-DD/events.jsonl` | `diff(snapshot[d-1], snapshot[d])`, sorted by event id | `diff` |
| Reports | `s3://lv-lake/reports/date=YYYY-MM-DD/report.json` | run record and quality report | `quality` |
| Marts | `s3://lv-marts/<model>.parquet` | dbt marts: companies, events, stats, value feature | dbt |
| Serving | Elasticsearch | latest company state, events, runs, stats, features | `index` |

**Storage:** SeaweedFS provides the S3 API locally (`lake` service), as in Oversee. Java uses
AWS SDK v2 `S3Client` with an endpoint override and path-style access; DuckDB and dbt use the
`httpfs` extension with the same settings. Pointing `S3_ENDPOINT` at nothing and using AWS
credentials moves everything to AWS S3. A local-directory implementation of the `storage`
interface exists for unit tests.

**History:** UR publishes only the current state. The lake is the only copy of past days, so
reprocessing and backfills always read from the lake, never from UR. A day that is not
downloaded on the day is lost, which is why freshness has its own alert.

DuckDB reads all days of a file as one table:

```sql
SELECT date, count(*) FROM read_parquet('s3://lv-lake/register/*/*.parquet', hive_partitioning = true)
GROUP BY date;
```

Nothing under `data/` or in the buckets is committed: it contains personal data.

## Pipeline steps

Each step reads objects and writes objects for one day, so it can run alone. The CLI is
`lv-register <step> --date YYYY-MM-DD`. Dagster runs the same commands in the `cli` image.

1. **download** (`ingest`): fetch each UR file with `java.net.http.HttpClient`, computing
   sha256 while streaming, write to a temporary key, then copy into place. A file whose
   sha256 is already in the manifest is not stored again.
2. **lake** (`lake`): DuckDB JDBC converts each CSV to Parquet (`COPY ... TO 's3://...'`),
   with source sha256 and size in the key-value metadata. If the day's Parquet already
   carries the same sha256, the step does nothing.
3. **normalize** (`normalize`): pure functions turn lake rows into domain records. Each
   returns `Cleaned<T>(rows, droppedByRule)`:
   - trim, Unicode NFC, collapse whitespace
   - validate the registration number format
   - map Latvian legal forms and statuses to North-Data-style families; unknown values become
     `Other` and are counted, never dropped silently
   - drop rows that break a rule, counted per rule; drop duplicate registration numbers,
     counted
4. **diff** (`diff`): compare yesterday's snapshot A with today's B (rules below). Writes the
   day's events.
5. **quality** (`quality`): run record and quality report (PLAN.md section 11).
6. **index** (`index`): upsert companies, events, the run report, and the stats and feature
   marts into Elasticsearch.

dbt runs between `quality` and `index` (see Orchestration).

## Event derivation

| Condition, A → B | Event |
|---|---|
| in B, not in A | `Registered` |
| name differs | `NameChanged(old, new)` |
| normalized address differs | `AddressChanged(old, new)` |
| legal form differs | `LegalFormChanged(old, new)` |
| officer set differs | `ManagementChanged(added, removed)` |
| member, stockholder or beneficial owner set differs | `OwnershipChanged(added, removed)` |
| status differs | `StatusChanged(old, new)` |
| termination date appears | `Terminated(date)` |
| in A, not in B | `MissingFromSnapshot` (flagged for review) |

- Comparison runs on normalized values, so formatting noise does not create events.
- The first snapshot is the baseline and creates no events.
- One company can produce several events on one day.
- `CompanyEvent` is a sealed interface. Handlers use an exhaustive `switch`, so a new event
  type fails compilation until every handler covers it.
- The Java diff is the source of truth. dbt computes the same diff in SQL; a dbt test
  requires both to match for every day.

## Idempotency

Re-running any step for a day leaves every output unchanged.

| Layer | Mechanism |
|---|---|
| Raw | skip a file whose sha256 is in the manifest; write via temp key + copy |
| Lake | skip a day whose Parquet metadata has the same source sha256 |
| Events | event id = `sha256(regNumber \| eventType \| snapshotDate \| canonicalPayload)`; objects sorted by id; temp key + copy |
| Marts | dbt full rebuild from lake and events |
| Elasticsearch | document id = registration number, event id or run id: upserts |
| Dagster | one partition per day; re-materializing a partition reruns the idempotent steps |

Canonical payloads are JSON with sorted keys and sorted sets, so the same change always gives
the same bytes and the same id.

## Java modules

```
            ┌──────────────── cli (Picocli, manual wiring) ────────────────┐
            ▼                                                              ▼
ingest  lake  normalize  diff  quality  index  telemetry  pipes          api (Spring Boot)
  │      │       │        │      │        │        │        │              │
  └──────┴───────┴────────┴──┬───┴────────┴────────┴────────┘              │
            storage ◄────────┤                                             │
                             ▼                                             │
                          domain ◄─────────────────────────────────────────┘
```

| Module | Holds | May depend on |
|---|---|---|
| `domain` | records, sealed types, invariants in compact constructors; no I/O | nothing |
| `storage` | `ObjectStore` interface; S3 and local-directory implementations | `domain` |
| `ingest` | downloader, sha256 manifest | `domain`, `storage` |
| `lake` | DuckDB JDBC reader and writer | `domain`, `storage` |
| `normalize` | row → record functions, legal form and status mapping | `domain` |
| `diff` | `diff(Snapshot, Snapshot)`, event ids | `domain` |
| `quality` | run record, report | `domain` |
| `index` | Elasticsearch upserts and read queries | `domain` |
| `telemetry` | OTel SDK setup, metric and span names, MDC keys | none of the above |
| `pipes` | Dagster Pipes messages (materializations, metadata, check results) | `domain` |
| `cli` | one Picocli command per step; `Main` builds every object by hand | pipeline modules |
| `api` | Spring Boot controllers, DTO records, web config | `domain`, `index` |

Rules, checked by ArchUnit in the build:
- Only `api` imports `org.springframework`.
- `domain` imports nothing outside the JDK.
- No pipeline module depends on `cli` or `api`.

The pipeline is batch code where a framework adds nothing, so it is plain Java. The API needs
rate limiting, CORS, validation, error handling, health checks and OpenAPI, which Spring Boot
provides (ADR 4).

## Domain model

The sketch in PLAN.md section 7 is final only after the source assessment. Fixed rules:

- Records only, no setters, no `null`: optional values are `Optional<T>`.
- Variants are sealed interfaces: `LegalForm`, `CompanyStatus`, `RoleType`, `CompanyEvent`.
- Invariants live in compact constructors, for example a valid `RegNumber` format.
- `Snapshot` is the full state of one day. `diff` is a pure function of two snapshots.

**Persons:** the open data may not give a stable person id. `PersonKey` is built from what
the data offers, documented in the source assessment and ADR 3. Matching is conservative: a
false merge of two people is worse than a missed match.

## Transformation with dbt

`transform/` is a dbt project on **dbt-duckdb**. DuckDB reads the lake and events straight
from S3, so there is no warehouse database to load. Marts are written back to S3 as Parquet
(external materialization); the DuckDB database file only holds views and build state.

| Layer | Materialized | Models (names finalized after Milestone 1) |
|---|---|---|
| Sources | Parquet and JSON Lines in S3 | one per MVP lake file, `events`, `reports` |
| Staging | views | `stg_<file>`: rename and cast only, one per source |
| Intermediate | views | `int_company_roles` (officers, members, stockholders, BOs as one role set), `int_snapshot_diff` (the diff in SQL) |
| Marts | Parquet in `s3://lv-marts` | `dim_companies` (latest state), `fct_company_events`, `agg_companies_by_form_status_region`, `agg_events_daily`, `feat_mass_registration_addresses` |

```
lake files ─► stg_* ─┬─► int_company_roles ─┬─► dim_companies ─┬─► agg_companies_by_form_status_region
                     │                      │                  └─► feat_mass_registration_addresses
                     └─► int_snapshot_diff ─┤
events ─────► stg_events ───────────────────┴─► fct_company_events ─► agg_events_daily
```

**Tests:**
- generic: unique and not-null keys, `relationships` from roles to companies (orphans),
  `accepted_values` for mapped legal forms and statuses
- singular, in `transform/tests/`:
  - `int_snapshot_diff` equals the Java events for every day (parity)
  - no events on the baseline day
  - event ids unique per day
  - every company in `dim_companies` appears in the latest lake snapshot
- severity: parity and key tests fail the build; drift checks (unmapped values) warn

**Exposures** declare the API and the frontend, so the lineage graph in `make dbt-docs` ends
at the products that use the data. dbt sources carry `meta.dagster.asset_key`, so Dagster
draws one graph from the download to the marts.

## Orchestration with Dagster

`orchestration/` is a small Python project: Dagster definitions plus pytest tests. Dagster
runs as three always-on services, with Postgres for its run and event storage:

```
dagster-webserver (:3000 UI) ─┐
dagster-daemon (schedules,    ├─► dagster-postgres
  sensors, run queue)         │
dagster-code (gRPC) ──────────┘
     │ launches through the Docker socket
     ▼
cli container: lv-register <step> --date <partition>  ── Dagster Pipes messages ──► Dagster
```

**Java through Pipes:** the Java steps run in the `cli` image, launched by dagster-docker's
Pipes client. The `pipes` module in the CLI writes Dagster Pipes messages: materialization
metadata (rows, sha256, URIs, dropped per rule) and asset check results. The Dagster code
holds no pipeline logic. The official Java Pipes library is used if it fits; otherwise the
module writes the small JSON message protocol itself.

**Assets** (daily partitions, start = first captured day):

| Asset | Runs | Checks |
|---|---|---|
| `ur_raw_files` | `cli download`, retry with backoff | file count and sizes vs previous day |
| `lake_snapshot` | `cli lake` | schema unchanged vs source assessment (blocking) |
| `company_events` | `cli diff`; depends on `lake_snapshot` for d and d−1 (`TimeWindowPartitionMapping(start_offset=-1)`) | event spike per type (blocking above a hard limit) |
| `quality_report` | `cli quality` | drop share ≤ 1%, orphans and unmapped values not growing |
| dbt models | `dbt build` via dagster-dbt, dbt tests as asset checks | all dbt tests |
| `search_index` | `cli index` | document counts match the marts |

- **Jobs:** `daily_pipeline` (all assets for one partition); `rebuild_marts` (dbt only).
- **Schedule:** `daily_pipeline` once a day, Europe/Riga time, set from Milestone 1's finding
  of when UR publishes. On by default.
- **Freshness:** freshness checks on `ur_raw_files` and `search_index`, so a missed day turns
  red in the UI and fires an alert.
- **Sensors:** run failure sensor posts to the alert webhook.
- **Backfills:** re-materializing past partitions reads from the lake. `ur_raw_files` cannot
  be backfilled for past days; its partitions only exist for days actually captured.
- **Blocking checks** stop downstream assets, so a broken file never reaches Elasticsearch.

## Observability and monitoring

```
cli, api, dagster, dbt ── JSON logs (stdout) ──► alloy (Docker discovery) ──► loki ────┐
cli (OTel SDK), api (agent) ── OTLP metrics, traces ──► alloy ──► prometheus, tempo ───┤
elasticsearch, postgres exporters, seaweedfs ── /metrics ──► prometheus (scrape) ──────┤
                                                                                       ▼
                                                      grafana: dashboards, alerts ─► contact points
```

**Correlation:** every pipeline log line, metric and span carries `run_id`, `dagster_run_id`,
`step` and `partition_date` (MDC in Java, resource attributes in OTel). API log lines carry the
trace id. Grafana links a log line to its trace and to the Dagster run.

**Batch metrics:** CLI runs end before Prometheus could scrape them, so the CLI pushes metrics
over OTLP and flushes them before exit. Gauges such as "last successful partition date per
step" make freshness alerts possible.

**Dashboards** (provisioned from `docker/grafana/dashboards/`):

| Dashboard | Shows |
|---|---|
| Pipeline health | last successful day per step, run duration, failures, freshness |
| Data quality | rows kept and dropped per rule, orphans, unmapped values, events per type with thresholds |
| API | request rate, error rate, p50/p95/p99 latency, rate-limit rejections, cache hits |
| Infrastructure | Elasticsearch health, heap and disk; SeaweedFS storage; JVM; Postgres; container CPU and memory |

**Alerts** (Grafana rules as code; each links to `docs/runbook.md`):

| Alert | Why it matters |
|---|---|
| no successful `download` for today by the deadline | the day is lost for good |
| a step failed after retries | data stops flowing |
| drop share above 1% for a file | source format changed or a rule is wrong |
| event type above its spike threshold | usually a parsing problem |
| orphans or unmapped values grew day over day | source drift |
| API 5xx above 1% or p95 above 500 ms for 10 min | users affected |
| Elasticsearch not green, or a volume above 80% disk | serving at risk |

Thresholds start as listed and are tuned on real data. Dagster's checks and failure sensor
overlap on purpose: Dagster shows the asset that broke, Grafana shows the trend and alerts
when nothing ran at all.

**Personal data:** logs, metrics, spans and Dagster metadata carry registration numbers and
counts only, never person names or keys. A test runs the CLI on a fixture and scans its log
output. Grafana, Dagster, SeaweedFS and Elasticsearch listen on localhost only.

## Serving

**Elasticsearch** (single node):

| Index | Document id | Used for |
|---|---|---|
| `companies` | registration number | search, company page |
| `events` | event id | timelines, changes feed |
| `runs` | run id | pipeline page |
| `stats` | aggregate name + key | overview page |
| `features` | feature name + key | insights page |

**API** (`api`, read-only, under `/api`): the endpoints in PLAN.md section 13.
- DTO records separate from domain records.
- Paging on list endpoints, validated query parameters.
- Errors as RFC 7807 `ProblemDetail` from one `@RestControllerAdvice`.
- Bucket4j rate limit per IP; Caffeine cache for stats and search.
- CORS only for the Vite dev server.
- Actuator health (with Elasticsearch), Micrometer metrics over OTLP, OTel Java agent for
  traces, springdoc OpenAPI. The spec generates the frontend's TypeScript types.
- Persons appear only inside one company's response. No endpoint lists persons.

**Frontend** (`web/`, React + TypeScript + Vite): Overview, Search, Company, Network,
Changes, Pipeline and Insights pages. It talks only to the API, through TanStack Query and
the generated types. The Pipeline page links each run to its Dagster run and Grafana view. In
production Spring Boot serves `web/dist`, so API and frontend are one service.

## Containers

Every build, test and run happens in Docker; the host needs only Docker and git.

| Profile | Services |
|---|---|
| `core` | `lake` (SeaweedFS), `elasticsearch`, `api` |
| `orchestration` | `dagster-postgres`, `dagster-code`, `dagster-webserver`, `dagster-daemon` |
| `observability` | `alloy`, `prometheus`, `loki`, `tempo`, `grafana`, `es-exporter`, `pg-exporter` |
| `dev` | `build` (JDK 25, Gradle), `web-dev` (Node 22, Vite), `duckdb` |
| on demand | `cli` (launched by Dagster or `docker compose run`) |

| Port | Service |
|---|---|
| 8080 | API and frontend |
| 5173 | Vite dev server |
| 3000 | Dagster UI |
| 3001 | Grafana |
| 8333, 8888 | SeaweedFS S3 API, file browser |
| 9200 | Elasticsearch |

**Images:** one multi-stage `Dockerfile` for Java. Stage `web` builds `web/dist`; stage `jdk`
builds the jars with the Gradle wrapper; final stages `cli` and `api` copy their jar onto
`eclipse-temurin:25-jre`. `orchestration/Dockerfile` builds the Dagster image with the dbt
project inside. Versions are pinned, services have healthchecks, and dependants wait for
`service_healthy`.

**Testcontainers** tests in the `build` container start Elasticsearch and SeaweedFS through
the mounted Docker socket; `TESTCONTAINERS_HOST_OVERRIDE` points them at the host.

**Makefile:** `make up`, `make down`, `make build`, `make test`, `make run DATE=...`,
`make dbt-build`, `make dbt-docs`, `make dagster`, `make grafana`, `make web-dev`,
`make duckdb`. CI runs the same images and targets.

## Testing

| Test | Checks |
|---|---|
| Unit (JUnit 5, AssertJ) | each normalizer rule and mapping, each record invariant |
| Property (jqwik) | `diff(A, A)` is empty; applying `diff(A, B)` to A gives B for tracked fields; event ids stable and unique per day |
| Idempotency | running a day twice gives byte-identical objects |
| Integration (Testcontainers) | S3 storage against SeaweedFS; indexer and API queries against Elasticsearch |
| Web (`@WebMvcTest`) | controllers, validation, error format |
| Architecture (ArchUnit) | module rules above |
| dbt | generic and singular tests, Java/SQL diff parity |
| Dagster (pytest) | definitions load; asset graph, partition mapping and checks as expected |
| Privacy | no person data in CLI logs on a fixture run |
| Alerts | each alert fired once on purpose (Milestone 8) |

## Open questions

- **Spring Boot version:** the plan said Spring Boot 3. 3.5 is the last 3.x line, and its
  open-source support window is around mid-2026 (to verify). Spring Boot 4 is current. To
  decide in Milestone 2.
- **Which UR files are in the MVP:** Milestone 1.
- **When UR publishes each day:** Milestone 1; sets the schedule and the freshness deadline.
- **Person key:** depends on what the officer and owner files contain.
- **Dagster Pipes from Java:** the official Java library or a small own implementation;
  decided in Milestone 7.
