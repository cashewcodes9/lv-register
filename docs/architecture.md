# Architecture

lv-register turns the open data of the Latvian Register of Enterprises (Uzņēmumu
reģistrs, UR) into typed company snapshots and a feed of change events, then serves them
through a read-only API and a React app. Dagster schedules and checks every step, dbt models
and tests the data in SQL.

It follows the layer design of [Oversee](https://github.com/cashewcodes9/Oversee): raw cache,
S3 lake, cleaning with drop counts, dbt, Dagster assets. The domain logic is in Java 25.
Scope and milestones are in [PLAN.md](../PLAN.md). Column names and value sets come from
[source-assessment.md](source-assessment.md) (Milestone 1), not from this document. Why the
stack looks like this (serving store, dbt, Dagster, marts) is answered in [FAQ.md](FAQ.md).

## Layers

```
UR CSVs ─► s3://lv-raw ─► s3://lv-lake ─────► normalize ─► Snapshot ─► diff ─► s3://lv-lake/events
data.gov.lv  as published   Parquet per file    Java, pure    typed       Java    JSON Lines per capture
             + manifest     and day, unfiltered               records       │
                                   │                                        │
                                   └────────────► dbt (DuckDB) ◄────────────┘
                                                  staging ─► intermediate ─► marts ─► s3://lv-marts
                                                                                        │
              quality ─► s3://lv-lake/reports ─────────────────────────────────┐        │
                                                                               ▼        ▼
                                                     serving store (PostgreSQL): companies, events,
                                                              runs, stats, features ─► API ─► React
```

| Layer | Where | Holds | Written by |
|---|---|---|---|
| Raw | `s3://lv-raw/date=YYYY-MM-DD/` | UR files as published, `manifest.json` (name, URL, sha256, size, fetched at) | `ingest` |
| Lake | `s3://lv-lake/<file>/date=YYYY-MM-DD/<file>.parquet` | every row and column, typed by DuckDB, source sha256 in key-value metadata | `lake` |
| Snapshot | in memory | `Snapshot` record: companies and roles of one capture, keyed by registration number | `normalize` |
| Events | `s3://lv-lake/events/date=YYYY-MM-DD/events.jsonl` | `diff(previous capture, this capture)`, sorted by event id | `diff` |
| Reports | `s3://lv-lake/reports/date=YYYY-MM-DD/report.json` | run record and quality report | `quality` |
| Marts | `s3://lv-marts/<model>.parquet` | dbt marts: companies, events, stats, value feature | dbt |
| Serving | PostgreSQL `lv_serving`, behind the `serving` port | latest company state, events, runs, stats, features | `index` through `ServingWriter` |

**Storage:** SeaweedFS provides the S3 API locally (`lake` service), as in Oversee. Java uses
the AWS SDK v2 `S3Client` only as an S3 protocol client, always with an endpoint override
(`S3_ENDPOINT`, required) and path-style access; DuckDB and dbt use the `httpfs` extension
with the same settings. Nothing talks to Amazon: any S3-compatible store works by pointing
`S3_ENDPOINT` at it. A local-directory implementation of the `storage`
interface exists for unit tests.

**History:** UR publishes only the current state. The lake is the only copy of past states, so
reprocessing always reads from the lake, never from UR. The demo captures once a week;
changes between two captures appear as one net change.

DuckDB reads all captures of a file as one table:

```sql
SELECT date, count(*) FROM read_parquet('s3://lv-lake/register/*/*.parquet', hive_partitioning = true)
GROUP BY date;
```

Nothing under `data/` or in the buckets is committed: it contains personal data.

## Pipeline steps

Each step reads objects and writes objects for one capture, so it can run alone. The CLI is
`lv-register <step> --date YYYY-MM-DD`. Dagster runs the same commands in the `cli` image.

1. **download** (`ingest`): look up the 11 MVP files by name in the CKAN catalog of
   data.gov.lv (`package_search` for organisation `ur`; a missing or duplicated file fails the
   step), fetch each with `java.net.http.HttpClient` (5xx and I/O errors retried, 4xx fails at
   once), compute sha256 while streaming, write to a temporary key, then copy into place. The
   manifest `date=D/manifest.json` records file, URL, sha256, size, fetch time and the CKAN
   `last_modified`. A file already in the capture's manifest is not downloaded again: a
   capture is fixed once written. Only today's capture (Riga date) is fetched; for a past date
   the step succeeds without fetching if the manifest is complete, and fails otherwise.
2. **lake** (`lake`): DuckDB JDBC reads each raw CSV through `httpfs` (`read_csv`, `;`, all
   columns VARCHAR, no Hive columns) and writes Parquet (ZSTD) with source file, sha256, size
   and capture date in the key-value metadata. DuckDB writes to a local work file, which is
   uploaded through a temporary key. If the day's Parquet already carries the manifest's
   sha256, the step does nothing.
3. **normalize** (`normalize`): pure functions turn lake rows into domain records. Each
   returns `Cleaned<T>(rows, droppedByRule, flaggedByRule)`:
   - trim, Unicode NFC, collapse whitespace
   - validate the registration number format
   - map Latvian legal forms and statuses to North-Data-style families; unknown values become
     `Other` and are counted, never dropped silently
   - drop rows that break a rule, counted per rule; drop duplicate registration numbers,
     counted
4. **diff** (`diff`): load the snapshot of this capture B and of the latest earlier capture A
   in the lake (`SnapshotLoader`: lake rows → normalizers → `Snapshot`), compare them (rules
   below) and write the events as JSON Lines, sorted by id. The first capture is the baseline
   and gets an empty events file. One line per event, keys sorted:
   `{"current":"2026-11-09","id":"<sha256>","payload":{…},"previous":"2026-11-02","regNumber":"…","type":"NAME_CHANGED"}`.
   Payloads are canonical (sorted keys, sets sorted by their JSON, plain decimals, absent values
   left out), so the same change always gives the same bytes and id. The step holds two
   snapshots in memory: the cli JVM runs with `-Xmx2g`.
5. **quality** (`quality`): reads the manifest, the Parquet row counts, the normalize counts that
   diff wrote (`reports/date=D/normalize.json`), the events and the bucket sizes, and writes the
   run record and quality report `reports/date=D/report.json` (PLAN.md section 11): sources,
   rows read and kept per file next to the previous capture, drops and flags per rule, NULLs in
   required columns, snapshot figures, events per type, storage, and warnings (`drop_share`,
   `row_change`, `unmapped_legal_form`, `unmapped_status`, `orphan_roles`, `duplicate_regcodes`,
   `event_spike`, `missing_from_snapshot`, `storage`). Status OK, WARN or FAILED (an input is
   missing; the step then fails). Thresholds come from `.env` (`QUALITY_*`, `STORAGE_WARN_GB`).
   Drops under a `*.legacy_*` rule (the old 10-digit reorganization codes) do not count for
   the drop share.
6. **index** (`index`): upsert companies, events, the run report, and the stats and feature
   marts into the serving store through `ServingWriter`.
7. **prune** (`quality`, `PruneStep`): bounded retention (see Captures, retention and on/off).

Every step returns its metadata and check results; under Dagster Pipes the CLI reports them
(see Orchestration).

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
| becomes closed: termination date appears, or status becomes TERMINATED / REORGANIZED | `Terminated(date)`, and no other event for that company in this capture (its roles vanish with it) |
| in A, not in B | `MissingFromSnapshot` (flagged for review) |

- Comparison runs on normalized values, so formatting noise does not create events.
- The first snapshot is the baseline and creates no events.
- One company can produce several events in one capture.
- `CompanyEvent` is a sealed interface. Handlers use an exhaustive `switch`, so a new event
  type fails compilation until every handler covers it.
- The Java diff is the source of truth. dbt computes the same diff in SQL; a dbt test
  requires both to match for every day.

## Idempotency

Re-running any step for a capture leaves every output unchanged.

| Layer | Mechanism |
|---|---|
| Raw | skip a file already in the capture's manifest; write via temp key + copy |
| Lake | skip a capture whose Parquet metadata has the same source sha256 |
| Events | event id = `sha256(regNumber \| eventType \| snapshotDate \| canonicalPayload)`; objects sorted by id; temp key + copy |
| Marts | dbt full rebuild from lake and events |
| Serving | key = registration number, event id or run id: `INSERT ... ON CONFLICT DO UPDATE` |
| Dagster | one partition per capture; re-materializing a partition reruns the idempotent steps |

Canonical payloads are JSON with sorted keys and sorted sets, so the same change always gives
the same bytes and the same id.

## Java modules

```
   ┌──────────── cli (Picocli, manual wiring) ─────┐
   ▼        uses every pipeline module             ▼
ingest  lake  normalize  diff  quality  index  pipes  serving-postgres   api (Spring Boot 4)
  │      │       │        │      │        │      │                │
  └──────┴───────┴────────┴──┬───┴────────┴──────┘                │
         storage ◄───────────┤                                    │
                             ▼                                    │
                          domain ◄────────────────────────────────┘
```

| Module | Holds | May depend on |
|---|---|---|
| `domain` | records, sealed types, invariants in compact constructors; no I/O | nothing |
| `storage` | `ObjectStore` interface; S3 and local-directory implementations | `domain` |
| `ingest` | CKAN lookup, downloader, sha256 manifest | `domain`, `storage` |
| `lake` | DuckDB JDBC session (httpfs), CSV → Parquet writer, row reader, `SnapshotLoader` | `domain`, `storage`, `ingest` (manifest, file list), `normalize` |
| `normalize` | row → record functions, legal form and status mapping, person keys, snapshot assembly | `domain` |
| `diff` | `SnapshotDiff` (pure), canonical event JSON, event ids, the diff step (I/O) | `domain`, `lake` (snapshot loading), `storage` |
| `quality` | run record and quality report (`ReportBuilder` pure, `QualityStep` I/O), asset checks (`Checks`, pure), retention (`PruneStep`) | `domain`, `lake`, `diff` |
| `index` | index step: loads the snapshot, events and report of a capture, writes through `ServingWriter` only | `domain`, `serving`, `lake`, `diff`, `quality` |
| `serving` | port: `ServingWriter`, `ServingQueries`, read records, `NetworkBuilder` (two-hop graph through the port); abstract contract test suite (test fixtures) | `domain` |
| `serving-postgres` | adapter: plain JDBC + HikariCP, Flyway migrations, `pg_trgm` + `unaccent` search on `company_search`, `party_roles` for the network | `domain`, `serving` |
| `pipes` | Dagster Pipes protocol 0.1: reads the injected context, writes materialization and check messages | Jackson only |
| `cli` | one Picocli command per step; `Main` builds every object by hand | pipeline modules |
| `api` | Spring Boot 4.1.2 read API: controllers, DTO records, ProblemDetail errors, rate limit, CORS, health, OpenAPI, cache | `serving`, `serving-postgres` (only in `ServingConfig`) |

Rules, checked by ArchUnit in the build:
- Only `api` imports `org.springframework`.
- `domain` imports nothing outside the JDK.
- No pipeline module depends on `cli` or `api`.
- Only `cli` and the API wiring configuration depend on a `serving-*` adapter; everything else
  uses the `serving` port.

The pipeline is batch code where a framework adds nothing, so it is plain Java. The API needs
rate limiting, CORS, validation, error handling, health checks and OpenAPI, which Spring Boot
provides (ADR 4).

## Domain model

The sketch in PLAN.md section 7 is final only after the source assessment. Fixed rules:

- Records only, no setters, no `null`: optional values are `Optional<T>`.
- Variants are sealed interfaces: `LegalForm`, `CompanyStatus`, `RoleType`, `CompanyEvent`.
- Invariants live in compact constructors, for example a valid `RegNumber` format.
- `Snapshot` is the full state at one capture. `diff` is a pure function of two snapshots.

**Persons:** the open data may not give a stable person id. `PersonKey` is built from what
the data offers, documented in the source assessment and ADR 3. Matching is conservative: a
false merge of two people is worse than a missed match.

## Transformation with dbt

What dbt adds and what marts are: [FAQ](FAQ.md#what-does-dbt-add), [marts](FAQ.md#what-are-marts).

`transform/` is a dbt project on **dbt-duckdb** (dbt-core 1.12.6, dbt-duckdb 1.11.1, DuckDB
1.4.5), run in the `orchestration` image (`make dbt-build`). DuckDB reads the lake and the
events straight from S3, so there is no warehouse database to load. Marts are written back to
`s3://lv-marts` as Parquet (external materialization); the DuckDB file in `/tmp` only holds
views and build state.

| Layer | Materialized | Models |
|---|---|---|
| Sources | Parquet in S3 | `lake.<file>`: one per MVP file, all captures, all columns VARCHAR, plus `capture_date` and `file_row_number`. The events are read by the macro `read_events()` (JSON Lines, explicit columns, so the empty baseline file is fine) |
| Seeds | table | `legal_forms`: the legal form table; `LegalFormsSeedTest` keeps it equal to the Java `LegalForms` |
| Staging | views | `stg_<file>`, `stg_events`: clean and cast only, with the macros `clean_text`, `clean_code`, `reg_number`, `clean_date`, `clean_decimal` (the same rules as `lvregister.normalize.Text`) |
| Intermediate | views | `int_companies` (validity, first duplicate, status, capital), `int_company_roles` (roles as value signatures), `int_snapshot_diff` (the diff in SQL) |
| Marts | Parquet in `s3://lv-marts` | `dim_companies` (latest state), `fct_company_events`, `agg_companies_by_form_status_region`, `agg_events_per_capture`, `feat_mass_registration_addresses` |

```
lake files ─► stg_* ─┬─► int_companies ─┬─► int_company_roles ─┬─► dim_companies ─┬─► agg_companies_by_form_status_region
legal_forms (seed) ──┘                  │                      │                  └─► feat_mass_registration_addresses
                                        └──────────────────────┴─► int_snapshot_diff ─┐ (parity test)
events ─────► stg_events ─────────────────────────────────────────► fct_company_events ┴─► agg_events_per_capture
```

**Parity.** `int_snapshot_diff` reimplements the Java normalization and diff rules in SQL.
The test `parity_sql_diff_equals_java_events` compares, for every capture the diff step ran
for, the capture date, the company, the event type, and the new value of name, status and
legal form changes. Ids and full payloads are not compared: rebuilding the canonical JSON in
SQL would add little. Breaking one SQL rule on purpose makes the test fail.

**Fixtures.** `transform/fixtures/lake` holds three synthetic captures (Parquet and
`events.jsonl`) that produce every event type plus formatting noise. `DbtFixturesTest`
(Java) generates them with the real lake and diff steps and fails when they drift
(`LV_WRITE_DBT_FIXTURES=1` rewrites them). `make dbt-fixtures` runs the whole dbt build on
them with the `fixtures` target, without S3; CI does the same.

**Tests:**
- generic: unique and not-null keys, `accepted_values` for statuses, legal families and event
  types; unmapped legal forms warn
- singular, in `transform/tests/`: parity (above), no events on the baseline capture, event ids
  unique per capture, every company in `dim_companies` is in the latest register capture,
  roles point to known companies (warns)

On the real captures the build takes about 4 minutes; `dim_companies` and the feature mart
match the Java normalizer and the source assessment (statuses, the top mass addresses with
185, 157, 132, 124 and 119 active companies).

**Exposures** declare the API and the frontend, so the lineage graph in `make dbt-docs` ends
at the products that use the data. dbt sources carry `meta.dagster.asset_key`, so Dagster
draws one graph from the download to the marts.

The index step loads the small marts into the serving store as `stats`
(`companies_by_form_status_region`, `events_per_capture`) and `features`
(`mass_registration_addresses`), replaced as a whole on every run.

## Orchestration with Dagster

What Dagster adds, and its cost: [FAQ](FAQ.md#what-does-dagster-add).

`orchestration/lvregister_orchestration` is a small Python package: Dagster definitions, a few
commands for the Makefile, and pytest tests. Dagster runs as three always-on services
(profile `orchestration`), with its run and event storage in the `dagster` database:

```
dagster-webserver (:3000 UI) ─┐
dagster-daemon (schedules,    ├─► postgres (database `dagster`)
  sensors, run queue,         │
  freshness)                  │
dagster-code (gRPC; runs      ┘
  execute here)
     │ PipesDockerClient through the Docker socket, network lv-register_default
     ▼
cli container: lv-register <step> --date <partition>
     │ stdout: JSON logs (Dagster compute logs)
     └ stderr: Pipes messages ─► materialization metadata + asset check results
```

- **One run at a time, one step at a time:** `QueuedRunCoordinator` with
  `max_concurrent_runs: 1` and the in-process executor. The heaviest step (`diff` or `index`,
  about 2.2 GB) then runs next to Dagster (about 0.45 GB idle) without overlap.
- **Config:** `docker/dagster/dagster.yaml` (instance) and `workspace.yaml` (the code location
  `lv_register` on `dagster-code:4000`), mounted read-only into the three services. Compute logs
  live on the `dagster-storage` volume they share.
- **Image:** `orchestration/Dockerfile`, built from the repository root. It holds Python,
  Dagster, dbt, the package, a copy of `transform/` and the dbt manifest from `dbt parse` at
  build time. The `dbt` tool service uses the same image with `./transform` mounted.

### Java through Pipes

The Java steps run in the `cli` image, launched by dagster-docker's `PipesDockerClient`. The
`pipes` module implements Dagster Pipes protocol 0.1 itself (about 200 lines on the JDK and
Jackson 3): it reads `DAGSTER_PIPES_CONTEXT` and `DAGSTER_PIPES_MESSAGES` (base64 of zlib of
JSON) and writes one JSON line per message (`opened`, `report_asset_materialization`,
`report_asset_check`, `closed`) to stderr. The official Java library was not used: its
repository is archived as a prototype, and it pulls in Jackson 2 and the AWS SDK.

Every step returns a `StepOutcome`: metadata (counts, sha256, URIs; never person data) and its
check results. The checks are computed in Java (`lvregister.quality.Checks`), and the Dagster
definitions declare the same names; a pytest compares the two lists. A failed blocking check is
reported with severity ERROR, which stops the downstream assets. Without the Pipes variables
(`make run`, a manual `docker compose run cli`) the CLI behaves as before. The cli container
gets only the forwarded variables of `FORWARDED_ENV` in `java_assets.py`, and is removed after
the step.

### Assets

One partition per capture actually made (`DynamicPartitionsDefinition` `captures`, keyed by
the Riga date). Every asset uses it, with identity mappings; `diff` reads the previous capture
from the lake itself, whatever its date.

| Asset | Runs | Checks (blocking in bold) |
|---|---|---|
| `ur_raw_files` | `cli download`, retry 3× with exponential backoff from 5 min; freshness policy: fails after 8 days without a capture | **`files_complete`**, `size_change` (vs previous capture) |
| `lake_snapshot` | `cli lake` | **`schema_unchanged`** (no column of the previous capture missing) |
| `company_events` | `cli diff` | **`event_spike`** (one event type above `QUALITY_EVENT_HARD_SHARE` of the companies) |
| `quality_report` | `cli quality` | **`drop_share`**, **`register_not_empty`**, `orphan_roles`, `unmapped_values`, `row_change`, `duplicate_regcodes`, `event_volume` |
| dbt models (group `dbt`) | `dbt build` via dagster-dbt; every staging model also depends on `quality_report` | the dbt tests; a failing test fails the step |
| `search_index` | `cli index`; depends on the stats, feature and `dim_companies` marts | `counts_match_marts` (latest capture only) |
| `retention` | `cli prune` | `storage_below_warn` (`STORAGE_WARN_GB`) |

- **Jobs:** `capture_pipeline` (all assets of one capture), `reprocess_capture` (from
  `company_events` on: reads only the lake), `rebuild_marts` (dbt only), `dagster_housekeeping`
  (deletes runs older than `DAGSTER_RUN_RETENTION_DAYS`, 90; OSS Dagster has no setting for
  run retention, only for ticks).
- **Schedules:** `weekly_capture`, Mondays 06:00 Europe/Riga (`LV_CAPTURE_CRON`). It adds the
  capture date as a partition and queues `capture_pipeline`. `daily_housekeeping` at 03:30.
  `make capture-now` does what the schedule does, at any time.
- **Sensor:** `run_failure_alert` logs a failed run and posts it to `LV_ALERT_WEBHOOK_URL` when
  set.
- **Reprocessing:** a past capture can be re-materialized from the UI (or `make capture-now
  DATE=...`). `download` never fetches a past date: with a complete manifest it succeeds
  without fetching; otherwise it fails. `diff` refuses when the capture before it was pruned
  from the lake, so events written earlier are never replaced by a diff across a longer gap.
- **Existing captures:** `make dagster` runs `sync-partitions`, which adds a partition for
  every raw manifest (captures made with `make run` or before Dagster).

## Captures, retention and on/off

Details and defaults are in PLAN.md section 6.2.

```
weekly_capture (Mon 06:00) ─┐
make capture-now ───────────┴─► capture_pipeline(date) ─► … ─► search_index ─► retention (prune)

make capture-off / capture-on   schedule state in Dagster; stack keeps running
make down / make up             all containers; data stays in volumes
```

- **Diff across gaps:** a capture is compared with the previous capture in the lake. Each
  event carries both snapshot dates (`observedBetween`), and the report records the gap.
- **Retention:** raw CSVs for the latest 2 captures, lake snapshots for the latest 12, events
  and reports forever (`RAW_KEEP`, `LAKE_KEEP`). Raw manifests are kept too: they list every
  capture ever made. `prune` never deletes the latest capture, raw CSVs the lake does not hold
  yet, or a snapshot before its own events and those of the next capture (whose diff reads it)
  are written.
- **Restart policy:** `unless-stopped`, so a running stack survives a reboot and a stopped one
  stays stopped.

## Logging and monitoring

The MVP keeps what is needed to notice a missed or broken capture. Metrics, traces, dashboards
and alerts come later ([ROADMAP.md](../ROADMAP.md), section 1).

- **Logs:** every container logs JSON to stdout. Pipeline lines carry `run_id`,
  `dagster_run_id`, `step` and `partition_date` (SLF4J MDC), so one run can be followed from
  `docker compose logs` or the Dagster run view.
- **Dagster:** asset checks, freshness checks, the run failure sensor and retries (above) are
  the alerting layer for now.
- **Health:** Actuator health for the API with a database indicator; healthchecks on
  every compose service.
- **Pipeline page:** the frontend shows run history and quality reports from the `runs` table.

**Personal data:** logs and Dagster metadata carry registration numbers and counts only,
never person names or keys. A test runs the CLI on a fixture and scans its log output.
Dagster, SeaweedFS and PostgreSQL listen on localhost only.

## Serving

Why PostgreSQL and not Elasticsearch: [FAQ](FAQ.md#why-postgresql-for-serving-and-not-elasticsearch).

The serving store sits behind a port, so the backend can be swapped without touching the
pipeline or the API:

```
index (step) ──► ServingWriter ─┐                      ┌─► serving-postgres   (MVP)
                                ├─ serving (port) ─────┤
api ──────────► ServingQueries ─┘                      └─► serving-elasticsearch (later, ROADMAP.md)
```

- `serving` holds the two interfaces and the read records they return (search hits, company
  view, event page, run summaries, stats). They speak domain and read records only: no SQL
  types, no JDBC, no Elasticsearch types.
- `serving` also publishes an abstract **contract test suite** (Gradle test fixtures): load a
  fixture through `ServingWriter`, then check every `ServingQueries` method, including upsert
  idempotency and search with and without diacritics. Every adapter runs it; a new adapter
  counts as done only when it passes.
- `SERVING_BACKEND=postgres` (default) picks the adapter: `cli` wires it by hand, the API in
  one `@Configuration` class.

**PostgreSQL adapter** (`serving-postgres`, database `lv_serving`, PostgreSQL 18): plain JDBC
with HikariCP; schema in Flyway migrations (`db/migration/serving`, history table
`serving_schema_history`), applied by `ServingWriter.prepare()` before the index step writes.

| Table | Key | Used for |
|---|---|---|
| `companies` | registration number | search, company page: the `CompanyView` document as `jsonb`, plus `name_search`, status, legal family, `as_of`, `doc_hash` |
| `events` | event id | timelines, changes feed; payload as `json` (keeps the canonical text); indexes on registration number + date and on date + type |
| `runs` | run id | pipeline page; the quality report as `json` |
| `party_roles` | party key + registration number + role | the company network: which companies a person (`person:<key>`) or entity (`company:<reg>`) holds roles in; derived from `companies.doc` by a trigger (V3) |
| `company_search` | registration number | search: a narrow copy of name, unaccented name, legal form and status with the GIN trigram index, kept by a trigger (V4) |
| `stats` | aggregate name + key | overview page (Milestone 6) |
| `features` | feature name + key | insights page (Milestone 6) |

Search: extensions `pg_trgm` and `unaccent`; a generated, unaccented, lower-case name column
(through an `IMMUTABLE` wrapper function, because `unaccent()` itself is only `STABLE`)
with a GIN trigram index on the narrow `company_search` table. Matches are a registration number, a substring (`LIKE`, wildcards in
the query escaped) or trigram similarity; ranked by similarity, an exact registration number
first. "riga udens" finds `SIA "Rīgas ūdens"`. Search first ran on `companies` itself: its
heap holds every document (501 MB), and a common word ("nams", 1,901 hits) read about 8,600
random heap blocks, 6 s cold on the dev VM. `company_search` is about 60 MB and stays in
memory. A query whose substring matches more than 1,000 names (a common word such as
"sabiedriba", 193,000 names) is "broad": it returns the substring matches only, ranked exact
name, prefix, then shorter names, without trigram similarity over every match. Measured on the
real register through the API: typical queries 0.03–0.4 s, the commonest word about 2.8 s
(6 s before), and the API caches results for 10 minutes (Caffeine).

Loads are upserts. A company row is only written when its content hash (the document without
`as_of`) changed and the incoming capture is not older (`as_of`), so re-indexing a capture
writes nothing and an older capture never rolls back a newer one. On the real data the first
capture writes 489,963 companies (3.5 min); the next one writes only the 4 that changed.

**API** (`modules/api`, Spring Boot 4.1.2, read-only, `http://localhost:8080/api`; Swagger UI
at `/api/docs`, spec at `/api/openapi.json`): the endpoints in PLAN.md section 13.

| Endpoint | Returns |
|---|---|
| `GET /api/search?q=&page=&size=` | search hits (cached 10 min) |
| `GET /api/companies/{reg}` | company with officers, owners, beneficial owners |
| `GET /api/companies/{reg}/events` | the company's events, newest capture first |
| `GET /api/companies/{reg}/network` | two-hop graph: the company, its parties, their other companies, the parties of those; at most 30 other companies and 300 nodes (`truncated` says when cut) |
| `GET /api/events?date=&type=` | changes feed |
| `GET /api/stats` | companies per legal form, status and region; events per capture (cached) |
| `GET /api/runs`, `GET /api/runs/{id}` | pipeline runs; one run with its quality report |
| `GET /api/features/mass-addresses` | mass-registration addresses, best rank first |

- Reads only through `ServingQueries`; only `ServingConfig` knows the adapter, and nothing in
  the API may use `ServingWriter` (ArchUnit). The API never migrates: the index step does.
- DTO records (`api.dto.Dtos`) separate from the serving views: `null` instead of `Optional`,
  stored JSON (payloads, reports, feature rows) embedded as JSON.
- Paging on list endpoints (`page` from 0, `size` 1–100), validated parameters (registration
  number `\d{9}|\d{11}`, ISO dates, event types).
- Errors as RFC 9457 `ProblemDetail` (`application/problem+json`): 400, 404, 429.
- Bucket4j rate limit per client IP: a burst of 30, refilled at 120 per minute
  (`API_RATE_LIMIT_*`); 429 with `Retry-After`. The socket address is the client: no proxy is
  trusted.
- CORS for the Vite dev server only (`API_CORS_ORIGINS`), GET and HEAD.
- Actuator `health` with a `servingStore` indicator (runs, latest capture) and readiness
  probe; springdoc OpenAPI. The spec generates the frontend's TypeScript types (Milestone 9).
- Network graph: `NetworkBuilder` (plain Java, `serving` module) over two port methods,
  `companies(regs)` and `rolesOfParties(keys, limit)`, backed by `party_roles` in Postgres.
- Persons appear only in the context of one company: its page and its network graph
  (PLAN.md section 12). No endpoint lists or exports persons in bulk; `personKey` is the
  pseudonymous key that links one person across companies.
- Real data on the VM: the API uses about 200 MB (`-Xmx256m`); the network of the company
  whose party has the most roles answers in 0.05 s (105 nodes, truncated).

**Frontend** (`web/`, React + TypeScript + Vite): Overview, Search, Company, Network,
Changes, Pipeline and Insights pages. It talks only to the API, through TanStack Query and
the generated types. The Pipeline page links each run to its Dagster run. In
production Spring Boot serves `web/dist`, so API and frontend are one service.

## Containers

Every build, test and run happens in Docker; the host needs only Docker and git.

| Profile | Services |
|---|---|
| `core` | `lake` (SeaweedFS), `postgres` (databases `lv_serving`, `dagster`), `api` |
| `orchestration` | `dagster-code`, `dagster-webserver`, `dagster-daemon` |
| `dev` | `web-dev` (Node 22, Vite) |
| `tools` (run only) | `build` (JDK 25, Gradle), `cli` (launched by Dagster or `docker compose run`), `duckdb` |

| Port | Service |
|---|---|
| 8080 | API and frontend |
| 5173 | Vite dev server |
| 3000 | Dagster UI |
| 8333, 8888 | SeaweedFS S3 API, file browser |
| 5432 | PostgreSQL |

**Images:** one multi-stage `Dockerfile` for Java. Stage `web` builds `web/dist`; stage `jdk`
builds the jars with the Gradle wrapper; final stages `cli` and `api` copy their jar onto
`eclipse-temurin:25-jre`. `orchestration/Dockerfile` builds the Dagster image with the dbt
project inside. Versions are pinned, services have healthchecks, and dependants wait for
`service_healthy`.

**Testcontainers** tests in the `build` container start PostgreSQL and SeaweedFS through
the mounted Docker socket; `TESTCONTAINERS_HOST_OVERRIDE` points them at the host.

**Makefile:** `make up`, `make down`, `make build`, `make test`, `make run DATE=...`,
`make capture-now`, `make capture-off`, `make capture-on`, `make status`, `make dbt-build`,
`make dbt-docs`, `make dagster`, `make web-dev`,
`make duckdb`. CI runs the same images and targets.

## Testing

| Test | Checks |
|---|---|
| Unit (JUnit 5, AssertJ) | each normalizer rule and mapping, each record invariant |
| Property (jqwik) | `diff(A, A)` is empty; applying `diff(A, B)` to A gives B for tracked fields; event ids stable and unique per capture |
| Idempotency | running a capture twice gives byte-identical objects |
| Retention | `prune` on fixtures keeps the configured captures and never the wrong ones |
| Integration (Testcontainers) | S3 storage against SeaweedFS; the serving contract suite against PostgreSQL |
| Web (`@WebMvcTest`) | controllers, validation, error format |
| Architecture (ArchUnit) | module rules above |
| dbt | generic and singular tests, Java/SQL diff parity |
| Dagster (pytest) | definitions load; asset graph, partition mapping and checks as expected |
| Privacy | no person data in CLI logs on a fixture run |

## Open questions

- **Which UR files are in the MVP:** Milestone 1.
- **When UR publishes updates:** Milestone 1; sets the weekly schedule time.
- **Person key:** depends on what the officer and owner files contain.
- **Dagster Pipes from Java:** the official Java library or a small own implementation;
  decided in Milestone 7.
