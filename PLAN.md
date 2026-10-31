# lv-register: Latvia Company Data Pipeline

You are helping me build a portfolio project for the North Data "Modern Java Developer"
application (Hamburg). Follow this plan milestone by milestone. Ask me before changing scope.

Goal: show that I can take a country North Data does not cover yet, understand its sources on
my own, and turn them into clean, tested, event-based company data, with a React frontend to
explore and check it. The pipeline is orchestrated, observable and monitored like a
production data platform.

---

## 1. Why Latvia

- North Data covers Estonia and Lithuania (Lithuania only in beta, base data only) but not
  Latvia. Latvia is the missing Baltic country.
- The Latvian Register of Enterprises (Uzņēmumu reģistrs, UR) publishes open data on
  data.gov.lv under CC0, updated daily.
- About 480K entities across these files: company register, officers, members (SIA
  shareholders), stockholders (AS shareholders), beneficial owners, equity capitals, areas of
  activity.
- Sources:
  - Open data portal: https://data.gov.lv/dati/lv/organization/ur
  - UR open data info: https://ur.gov.lv/en/specialized-information/open-data
  - Public search (for spot checks): https://info.ur.gov.lv
- UR publishes only the current state. Past days cannot be downloaded again, so the lake is
  the only history this project has. A missed day is lost for good, which makes scheduling
  and freshness monitoring essential, not optional.

## 2. Reference design: Oversee

This project re-applies the architecture of my Python project Oversee
(github.com/cashewcodes9/Oversee). Read its README.md, docs/architecture.md and ROADMAP.md
before starting. Keep these patterns:

- Raw files cached by day, fingerprinted with SHA-256, skipped if already processed.
- S3-compatible object storage (SeaweedFS locally) for raw files and the lake. The same code
  works against AWS S3 by changing configuration only.
- Hive-partitioned Parquet lake: `s3://lv-lake/<file>/date=YYYY-MM-DD/<file>.parquet`,
  unfiltered, with the source SHA-256 in the Parquet key-value metadata. DuckDB reads and
  writes it through `httpfs`.
- Cleaning as pure functions that return (rows, droppedCountsByRule).
- A run record per pipeline run (status, rows read, rows written, error) and a quality log of
  rows dropped per rule. Every log line carries the run id.
- Idempotent writes everywhere: re-running a day must not create anything new.
- dbt for SQL models, with generic and singular data tests, docs and lineage.
- Dagster for orchestration: software-defined assets, daily partitions, asset checks,
  backfills, schedules. Dagster calls the same code as the CLI.
- A Makefile and a Docker-only path.

Differences from Oversee:

| Topic | Oversee | lv-register |
|---|---|---|
| Pipeline language | Python (pandas) | Java 25 (the job's language). Python only for Dagster and dbt |
| Derived data | port calls from positions | company events from the diff of two daily snapshots (section 8) |
| Warehouse | PostgreSQL + dbt-postgres | DuckDB + dbt-duckdb over the Parquet lake; marts written back to S3 as Parquet. No Postgres for data |
| Serving | PostgreSQL | Elasticsearch |
| Dagster | `dagster dev`, schedule stopped | always-on webserver and daemon in Docker, daily schedule on, Postgres for Dagster's own run storage |
| Observability | JSON logs with run id | logs, metrics and traces in Grafana, dashboards and alerts as code (section 11.1) |

## 3. Language and style

Java 25, matching North Data's backend (modern Java, functional style).
- Immutable records, sealed interfaces, pattern matching for switch, record patterns,
  streams, Optional.
- Pure functions for all transformation logic. I/O only at the edges.
- No setters, no nulls in the domain model (use Optional), no inheritance hierarchies
  beyond sealed types.

Python (Dagster definitions) and SQL (dbt) stay thin: they orchestrate and check, the domain
logic lives in Java.

## 4. Tech stack

| Area | Choice |
|---|---|
| Language | Java 25 for pipeline and API; Python 3.12 for Dagster; SQL for dbt |
| Build | Gradle (Kotlin DSL), toolchain set to 25; `uv` for the Python project |
| Frameworks | Spring Boot in the `api` module only (version decided in Milestone 2). All pipeline modules are plain Java with no Spring imports. Manual wiring in the CLI `Main` |
| CLI | Picocli (one subcommand per pipeline step, `--date` option) |
| HTTP API | Spring Boot web, Bucket4j rate limiting, Actuator, springdoc-openapi, Caffeine cache, ProblemDetail errors |
| JSON | Jackson (with records support) |
| CSV | FastCSV or Apache Commons CSV |
| HTTP client | java.net.http.HttpClient |
| Object storage | SeaweedFS (S3 API) locally, AWS S3 compatible. Java uses AWS SDK v2 `S3Client` with endpoint override and path-style access |
| Lake | Parquet files written and queried with DuckDB (JDBC, `httpfs` extension) |
| Events | JSON Lines per day in S3 |
| SQL models and tests | dbt-core + dbt-duckdb: staging, intermediate and mart models over the lake, marts written as Parquet to S3 |
| Orchestration | Dagster (webserver, daemon, Postgres run storage) with dagster-dbt and dagster-docker. Java steps run in the `cli` image through Dagster Pipes |
| Search | Elasticsearch 8 |
| Observability | OpenTelemetry (Java agent for the API, OTel SDK in the CLI), Micrometer, Grafana Alloy (collector), Prometheus, Loki, Tempo, Grafana; exporters for Elasticsearch and Postgres; SeaweedFS metrics |
| Tests | JUnit 5, AssertJ, jqwik (property tests), Testcontainers (Elasticsearch, SeaweedFS), ArchUnit; pytest for Dagster definitions; dbt data tests |
| Logging | SLF4J + Logback, JSON to stdout, run id in MDC |
| Containers | Docker + Docker Compose. Every build, test and run happens in containers (section 6.1). The host needs only Docker and git |
| CI | GitHub Actions: backend build + test, dbt parse + build on fixtures, Dagster definitions load, frontend lint + build, Docker image builds |
| Frontend | React + TypeScript (Vite), TanStack Query for data fetching, Recharts for charts, react-force-graph or d3.js for the company-person network. Talks only to the API. In production, Spring Boot serves the built React app as static files, so it stays one service |

Why this split: the pipeline is batch code where a framework adds nothing, so it stays plain
Java and runs via Picocli. The React-facing API needs middleware (rate limiting, CORS,
validation, error handling, health, OpenAPI), so it uses Spring Boot, where these are
standard. Spring stays at the edge: domain and pipeline modules must not depend on it.
Dagster and dbt are the best tools for orchestration and SQL testing, and both are Python;
they call the Java pipeline through its container instead of reimplementing it. Document
these choices in ADRs.

## 5. Scope

### In scope (MVP)
1. Download the daily UR CSV files, with checksums and raw caching in S3.
2. Store each day as a Parquet snapshot in the lake.
3. Parse and normalize into an immutable domain model.
4. Derive change events by diffing consecutive snapshots.
5. Idempotent processing at every layer.
6. Run record and data quality report per run.
7. dbt models and tests over the lake and events: statistics, value feature, invariants.
8. Dagster orchestration: daily partitions, daily schedule, asset checks, retries, backfills
   from the lake.
9. Index companies, events and run reports into Elasticsearch.
10. One value feature (section 10).
11. Observability: logs, metrics and traces in Grafana, dashboards and alerts (section 11.1).
12. Spring Boot read API and React frontend to explore the data and check pipeline health.

### Out of scope
- Financial statements (filed with the State Revenue Service, VID). Stretch goal only.
- Authentication, user accounts, production deployment.
- Publishing personal data (section 12).
- High availability: every service is a single container.

## 6. Architecture

```
                        ┌──────────────────── Dagster (schedule, partitions, checks) ───────────────────┐
                        ▼                    ▼                       ▼                 ▼               ▼
UR CSVs ─► s3://lv-raw/date=…/*.csv ─► s3://lv-lake/<file>/date=…/*.parquet ─► normalize ─► diff ─► s3://lv-lake/events/date=…
data.gov.lv  (cache, sha256 manifest)   (DuckDB, unfiltered, source sha)     (Java, pure)  (Java)   (JSON Lines)
                                                       │                                              │
                                                       └──────────────► dbt (dbt-duckdb) ◄────────────┘
                                                                staging ─► marts ─► s3://lv-marts/*.parquet
                                                                tests: invariants, orphans, parity
                                                                              │
                         quality ─► s3://lv-lake/reports/date=…  ─────────────┤
                                                                              ▼
                              Elasticsearch (companies, events, runs, stats, features) ─► Spring Boot API ─► React

      every service ─► logs, metrics, traces ─► Grafana Alloy ─► Loki / Prometheus / Tempo ─► Grafana (dashboards, alerts)
```

Every pipeline step is a function from input objects to output objects, so each step can be
re-run alone:

```
lv-register download  --date 2026-11-02
lv-register lake      --date 2026-11-02
lv-register diff      --date 2026-11-02
lv-register quality   --date 2026-11-02
lv-register index     --date 2026-11-02
lv-register run       --date 2026-11-02     # all Java steps, without Dagster
```

Dagster runs the same subcommands in the `cli` image. The CLI also runs without Dagster,
for debugging. The CLI never boots Spring. All commands run in containers (section 6.1), for
example `docker compose run --rm cli download --date 2026-11-02`.

### 6.1 Containers

Every stack runs in Docker. Nothing is installed on the host except Docker and git, so the
project builds the same on any machine and in CI.

| Compose service | Image | Purpose |
|---|---|---|
| `lake` | `chrislusf/seaweedfs` (pinned) | S3 API (:8333) and file browser (:8888); buckets `lv-raw`, `lv-lake`, `lv-marts` |
| `elasticsearch` | `elasticsearch:8.x`, single node, security off locally | serving store, data volume |
| `build` | `eclipse-temurin:25-jdk` | runs the Gradle wrapper: `./gradlew build`, tests. Gradle cache in a named volume |
| `cli` | `Dockerfile` target `cli` (JRE 25 + CLI jar + OTel SDK) | runs pipeline steps; launched by Dagster or `docker compose run` |
| `api` | `Dockerfile` target `api` (JRE 25 + Spring Boot jar with `web/dist` + OTel Java agent) | API and frontend on one port |
| `web-dev` | `node:22` (LTS) | Vite dev server with hot reload; `npm` commands; `node_modules` in a named volume |
| `dagster-postgres` | `postgres:16` | Dagster run, event log and schedule storage only |
| `dagster-code` | `orchestration/Dockerfile` (Python, Dagster, dbt-duckdb, dbt project) | Dagster code location (gRPC); launches `cli` containers through the Docker socket |
| `dagster-webserver` | same image | Dagster UI (:3000): asset graph, runs, backfills, checks |
| `dagster-daemon` | same image | schedules, sensors, run queue, retries |
| `alloy` | `grafana/alloy` | collects container logs (Docker discovery), receives OTLP, scrapes metrics |
| `prometheus` | `prom/prometheus` | metrics store, alert rule evaluation |
| `loki` | `grafana/loki` | log store |
| `tempo` | `grafana/tempo` | trace store |
| `grafana` | `grafana/grafana` | dashboards and alerts, provisioned from `docker/grafana/` |
| `es-exporter`, `pg-exporter` | Prometheus community exporters | Elasticsearch and Dagster Postgres metrics |
| `duckdb` | small image with the DuckDB CLI | ad-hoc SQL over the lake (Milestone 1 and debugging) |

- Compose profiles keep the default stack small: `core` (lake, elasticsearch, api),
  `orchestration` (Dagster services), `observability` (Alloy, Prometheus, Loki, Tempo,
  Grafana, exporters), `dev` (build, web-dev, duckdb). `make up` starts all of them.
- One multi-stage `Dockerfile` for Java: stage `web` builds the React app with Node, stage
  `jdk` builds the jars with the Gradle wrapper, final stages `cli` and `api` copy the jars
  onto `eclipse-temurin:25-jre`. Production image = target `api`.
- Pin image versions (tag or digest). No `latest`.
- Containers run as a non-root user where the image allows it.
- Every long-running service has a healthcheck; dependants wait with
  `condition: service_healthy`.
- Testcontainers inside the `build` container: mount `/var/run/docker.sock` and set
  `TESTCONTAINERS_HOST_OVERRIDE` so tests reach the containers they start.
- Development credentials (SeaweedFS keys, Postgres password, Grafana admin) come from
  `.env` (copied from `.env.example`) and are for local use only.
- A `Makefile` wraps the common commands (`make up`, `make down`, `make build`, `make test`,
  `make run DATE=...`, `make dbt-build`, `make dbt-docs`, `make dagster`, `make grafana`,
  `make web-dev`, `make duckdb`), as in Oversee.
- CI uses the same images, so a green CI means a green local build.

### Module layout

```
lv-register/
  build.gradle.kts
  settings.gradle.kts
  Dockerfile                  multi-stage: web, jdk, cli, api
  docker-compose.yml          services and profiles from section 6.1
  Makefile                    wrappers around docker compose
  .env.example
  modules/
    domain/       records, sealed types, no I/O, no dependencies
    storage/      S3 object store (AWS SDK v2) and local-directory implementation
    ingest/       download, sha256 manifest, raw cache
    lake/         Parquet snapshots via DuckDB JDBC (httpfs)
    normalize/    lake rows -> domain, legal form + status mapping, dropped-row counts
    diff/         snapshot comparison -> events, deterministic event ids
    quality/      run record, quality report
    index/        Elasticsearch writer (upserts) and read queries
    telemetry/    OTel SDK setup for the CLI, metric names, MDC helpers; no Spring
    pipes/        Dagster Pipes messages from the CLI (materializations, checks, logs)
    cli/          Picocli commands, Main with manual wiring
    api/          Spring Boot read API under /api: controllers, DTO records, Bucket4j rate limit,
                  CORS for Vite dev server, Actuator, OpenAPI, serves web/dist in production
  orchestration/  Python project: Dagster definitions (assets, checks, jobs, schedules,
                  sensors), Dockerfile, pytest tests
  transform/      dbt project: sources, staging, intermediate, marts, tests, exposures
  web/            React + TypeScript app (Vite)
  docker/
    seaweedfs/    s3.json identities
    dagster/      dagster.yaml, workspace.yaml
    alloy/        config.alloy
    prometheus/   prometheus.yml, alert rules
    loki/ tempo/  configs
    grafana/      provisioning: data sources, dashboards (JSON), alert rules, contact points
  docs/
    source-assessment.md
    architecture.md
    runbook.md    what each alert means and what to do
    adr/
  .github/workflows/ci.yml
```

Dependency direction: `api` and `cli` depend on the pipeline modules, never the other way
round. `orchestration/` and `transform/` depend on the Java code only through the `cli`
image and the files it writes.

## 7. Domain model (sketch, finalize after Milestone 1)

```java
record RegNumber(String value) {}          // validate format in compact constructor

record Company(
    RegNumber regNumber,
    String name,
    LegalForm legalForm,
    CompanyStatus status,
    Address address,
    LocalDate registered,
    Optional<LocalDate> terminated
) {}

sealed interface LegalForm permits Sia, As, IndividualMerchant, Partnership, Other {}
// map Latvian forms (SIA, AS, IK, PS, KS, ...) to North-Data-style families:
// LLC, Corporation, Sole proprietorship, General/Limited partnership, ...

record Person(PersonKey key, String name) {}   // key is best-effort, section 9
record Role(RegNumber company, PersonKey person, RoleType type, LocalDate since) {}

record Snapshot(LocalDate date, Map<RegNumber, Company> companies,
                Map<RegNumber, Set<Role>> roles) {}

sealed interface CompanyEvent permits
    Registered, NameChanged, AddressChanged, LegalFormChanged,
    ManagementChanged, OwnershipChanged, StatusChanged, Terminated, MissingFromSnapshot {}

record Cleaned<T>(List<T> rows, Map<String, Long> droppedByRule) {}
```

IMPORTANT: before writing domain code, download one day of every file, record the real column
names and value sets in `docs/source-assessment.md`, and only then finalize the model. Do not
guess column names.

## 8. Event derivation rules

Compare yesterday's snapshot (A) with today's (B), keyed by registration number:

| Condition | Event |
|---|---|
| In B, not in A | Registered |
| Name differs | NameChanged(old, new) |
| Address differs (after normalization) | AddressChanged(old, new) |
| Legal form differs | LegalFormChanged(old, new) |
| Officer set differs | ManagementChanged(added, removed) |
| Members / stockholders / BO set differs | OwnershipChanged(added, removed) |
| Status differs (e.g. to liquidation) | StatusChanged(old, new) |
| Termination date appears | Terminated(date) |
| In A, not in B | MissingFromSnapshot (flag for review, removal is unusual) |

- Normalize before comparing (trim, Unicode NFC, collapse whitespace, consistent case for
  codes) so formatting noise does not create fake events.
- The first snapshot creates no events. It is the baseline.
- The Java diff is the source of truth. A dbt model computes the same diff in SQL, and a dbt
  test requires both to agree (section 9), as Oversee's clean_sql vs pandas test does.

## 9. Idempotency and correctness

- Raw layer: a file with the same sha256 as one already stored is skipped.
- Lake: writing the same day again with the same source sha256 is a no-op.
- Event ids are deterministic: `sha256(regNumber | eventType | snapshotDate | canonicalPayload)`.
  Re-running a day writes the same ids.
- Objects are written to a temporary key and copied into place, so a failed run never leaves
  a half-written object under the final key.
- Elasticsearch document id = company reg number, event id or run id, so indexing is an upsert.
- dbt marts are full rebuilds from the lake and events, so re-running gives the same result.
- Property tests (jqwik):
  - `diff(A, A)` is always empty.
  - Running the pipeline twice on the same input gives byte-identical output files.
  - Applying the events from `diff(A, B)` to A reproduces B for the tracked fields.
  - Event ids are stable across runs and unique within a day.
- dbt tests on real data:
  - unique and not-null keys; relationships (roles point to known companies)
  - accepted values for mapped legal forms and statuses
  - singular: the SQL diff equals the Java events for every day (parity)
  - singular: no events on the baseline day; event ids unique per day
- Person matching: the open data may not give a stable person id. Document what is available
  and match conservatively. False merges are worse than missed merges.

## 10. Value features (pick 1 for MVP, more if time)

1. Mass-registration addresses: addresses with an unusual number of registered companies.
   A known shell-company signal, relevant for North Data's investigative journalism users.
2. Serial officers: people on many boards, or linked to many companies created and terminated
   in a short window.
3. Cross-Baltic links: officers or owners who also appear in Estonian or Lithuanian
   registers.
4. Coverage gap check: Latvian companies that appear as shareholders in countries North Data
   already covers.

Default choice: feature 1, as a dbt mart (`feat_mass_registration_addresses`) with its own
tests, indexed into Elasticsearch for the API.

## 11. Run record and data quality report

Per run, write `s3://lv-lake/reports/date=YYYY-MM-DD/report.json` with:
- run id (and the Dagster run id when launched by Dagster), start/end time, status, error
- source file names, sha256, sizes
- rows read and rows kept per file, change vs previous day
- rows dropped per cleaning rule
- missing required fields per column
- officers or owners pointing to unknown companies (orphans)
- duplicate registration numbers
- unmapped legal forms or statuses
- events per type, with a warning when a type spikes beyond a threshold (a spike usually
  means a parsing problem, not real change)

The same figures go to three places:
- Dagster: materialization metadata and asset checks (via Pipes), so a bad day is visible in
  the asset graph and can block downstream assets.
- Metrics (section 11.1), for dashboards and alerts over time.
- Elasticsearch (`runs` index), so the API and frontend show pipeline health.

### 11.1 Observability and monitoring

**Signals:**

| Signal | Source | Path | Store |
|---|---|---|---|
| Logs | all containers, JSON to stdout (Logback JSON encoder, Dagster JSON logs) | Alloy Docker discovery | Loki |
| Metrics, API | Micrometer via Actuator | OTLP to Alloy | Prometheus |
| Metrics, pipeline | OTel SDK in the CLI, flushed before exit (batch jobs cannot be scraped) | OTLP to Alloy | Prometheus |
| Metrics, infrastructure | Elasticsearch and Postgres exporters, SeaweedFS metrics endpoint, Alloy container metrics | Prometheus scrape | Prometheus |
| Traces | OTel Java agent in the API (HTTP, Elasticsearch client); spans per step in the CLI | OTLP to Alloy | Tempo |

- Correlation: every log line, metric and span of a pipeline run carries `run_id`,
  `dagster_run_id`, `step` and `partition_date`. API logs carry the trace id. Grafana links
  logs to traces and Dagster runs.
- Pipeline metrics (names finalized in Milestone 8): rows read, kept and dropped per file and
  rule; events per type; step duration; bytes downloaded; last successful partition date per
  step; Elasticsearch documents indexed.

**Dashboards** (JSON in `docker/grafana/dashboards/`, provisioned):
1. Pipeline health: last successful day per step, run durations, failures, freshness.
2. Data quality: rows kept vs dropped per rule, orphans, unmapped values, events per type
   over time with spike thresholds.
3. API: request rate, error rate, p50/p95/p99 latency, rate-limit rejections, cache hit rate.
4. Infrastructure: Elasticsearch cluster health, heap and disk; SeaweedFS storage; JVM; Dagster
   Postgres; container CPU and memory.

**Alerts** (Grafana alert rules as code, each with a runbook entry in `docs/runbook.md`):
- No successful `download` for today by the deadline (the day's data would be lost).
- Any pipeline step failed after its retries.
- Cleaning dropped more than 1% of rows of a file.
- Event count of a type above its spike threshold.
- Orphans or unmapped legal forms increased day over day.
- API 5xx rate above 1% or p95 latency above 500 ms for 10 minutes.
- Elasticsearch cluster not green; disk above 80% on any volume.

Thresholds are starting values, tuned once real data exists. Contact point locally: Grafana's
own notification list, plus an optional webhook (for example a Slack or Discord URL in `.env`).

Dagster adds its own layer: asset checks (warn or block), freshness checks on the daily
assets, a run failure sensor, and retry policies with backoff on network steps.

## 12. Personal data

Officers and beneficial owners are personal data.
- The public demo shows company-level data and aggregates only.
- The API returns persons only in the context of one company (its officers and owners, and
  its network graph). No endpoint lists or exports persons in bulk.
- Raw data, the lake and the marts are never committed. Tests use small, anonymized fixtures.
- Logs, metrics, traces and Dagster metadata never contain person names or person keys. They
  carry registration numbers and counts only. A test checks the CLI log output on a fixture.
- Grafana, Dagster, SeaweedFS and Elasticsearch are bound to localhost and not exposed.
- Note in the README and the source assessment that EU rules on public BO access may narrow
  Latvia's currently open model.

## 13. API (Spring Boot, read-only)

| Endpoint | For |
|---|---|
| `GET /api/search?q=` | Company search |
| `GET /api/companies/{reg}` | Company page: base data, officers, owners |
| `GET /api/companies/{reg}/events` | Event timeline |
| `GET /api/companies/{reg}/network` | Company-person graph around one company |
| `GET /api/events?date=&type=` | Changes feed |
| `GET /api/stats` | Counts per legal form, status and region; events per day |
| `GET /api/runs`, `GET /api/runs/{id}` | Pipeline runs and quality reports |
| `GET /api/features/mass-addresses` | Value feature results |

- DTOs as records, separate from domain records.
- Validation on query params, paging on list endpoints.
- Errors as RFC 7807 ProblemDetail via `@RestControllerAdvice`.
- Bucket4j rate limit per IP, simple configurable default.
- CORS allowed for the Vite dev server only.
- Actuator health (with Elasticsearch indicator), metrics exported over OTLP,
  springdoc-openapi (Swagger UI). Generate TypeScript types for the frontend from the OpenAPI
  spec.
- Caffeine cache for stats and search.

## 14. Frontend (React)

Pages:
1. **Overview:** headline stats (companies by legal form and status, events per day).
2. **Search:** company search with a results list.
3. **Company:** base data, officers and owners, event timeline.
4. **Network:** company-person graph around one company.
5. **Changes:** events per day, filter by type.
6. **Pipeline:** runs and quality reports (rows read/kept, dropped per rule, event spikes),
   with links to the Dagster run and the Grafana dashboard for that run.
   This is the "check what we have" view.
7. **Insights:** value feature results, e.g. mass-registration addresses.

Rules:
- Data fetching with TanStack Query only, against the generated API types.
- No mock data once the API exists. Build pages against real API responses.
- Keep styling simple and clean. Function over decoration.

## 15. Milestones

### Milestone 1: Source exploration
- [ ] Minimal `docker-compose.yml` with the `duckdb` service for exploring the files; no
      tools installed on the host
- [ ] Download one day of every UR file
- [ ] Record columns, types, value sets, row counts, encoding, delimiters, quirks in
      `docs/source-assessment.md`
- [ ] Find out when UR publishes each day's files (sets the schedule and freshness deadline)
- [ ] Map each field to a North Data feature (base data, history, representatives,
      shareholders, person networks, segment codes)
- [ ] Decide which files are in MVP
- [ ] STOP and show me the assessment before continuing

### Milestone 2: Project setup + domain
- [ ] Gradle multi-module, Java 25 toolchain
- [ ] Multi-stage `Dockerfile`; compose services `lake`, `elasticsearch`, `build`, `cli`,
      `duckdb` with healthchecks and profiles; `.env.example`; `Makefile`
- [ ] `make build` runs the Gradle build in the `build` container from a clean clone
- [ ] GitHub Actions CI (build + test)
- [ ] `domain` module: records and sealed types, one invariant test per record
- [ ] Picocli skeleton with all subcommands, Main with manual wiring
- [ ] ArchUnit test: no module except `api` imports `org.springframework`
- [ ] Logback JSON logging with run id in MDC

### Milestone 3: Storage + ingest + lake + normalize
- [ ] `storage` module: S3 object store against SeaweedFS (Testcontainers) and local directory
- [ ] Downloader with sha256 manifest in `s3://lv-raw`, skip if unchanged, write via temp key
- [ ] Lake writer: CSV to Parquet via DuckDB `httpfs`, source sha in metadata
- [ ] Normalizers per file returning `Cleaned<T>`
- [ ] Legal form and status mapping with tests

### Milestone 4: Diff + events
- [ ] Snapshot diff for all event types in section 8
- [ ] Deterministic event ids
- [ ] Property tests from section 9
- [ ] Collect two or more real daily snapshots and produce real events
- [ ] Spot-check 5 real events against info.ur.gov.lv

### Milestone 5: Quality + index
- [ ] Run record and quality report (section 11)
- [ ] Elasticsearch mappings for companies, events and runs
- [ ] Indexer with upserts, tested with Testcontainers
- [ ] Testcontainers working from the `build` container

### Milestone 6: dbt
- [ ] dbt project in `transform/` with dbt-duckdb reading the lake and events from S3
- [ ] Sources with tests; staging and intermediate models per MVP file
- [ ] Marts: `dim_companies`, `fct_company_events`, stats aggregates, value feature
      (section 10), written as Parquet to `s3://lv-marts`
- [ ] SQL diff model and the parity test against the Java events (section 9)
- [ ] Singular tests from section 9; exposures for the API and frontend
- [ ] `make dbt-build`, `make dbt-docs` (lineage graph)
- [ ] Indexer loads stats and feature marts into Elasticsearch

### Milestone 7: Dagster orchestration
- [ ] `orchestration/` Python project and image; compose services `dagster-postgres`,
      `dagster-code`, `dagster-webserver`, `dagster-daemon`
- [ ] `pipes` module: the CLI reports materializations, metadata and check results to Dagster
- [ ] Daily partitioned assets: `ur_raw_files` ─► `lake_snapshot` ─► `company_events`
      (depends on the previous day too) ─► `quality_report` ─► dbt models ─► `search_index`
- [ ] Java steps launched in the `cli` image through Dagster Pipes; dbt via dagster-dbt with
      dbt tests as asset checks
- [ ] Asset checks from the quality report (drop share, orphans, unmapped values, event
      spikes); blocking checks stop downstream assets
- [ ] Daily schedule (time from Milestone 1), freshness checks, run failure sensor, retry
      policy with backoff for `download`
- [ ] Backfill of past partitions from the lake (not from UR) works from the UI
- [ ] pytest: definitions load, asset graph and partition mapping are as expected

### Milestone 8: Observability and monitoring
- [ ] Compose profile `observability`: Alloy, Prometheus, Loki, Tempo, Grafana, exporters
- [ ] `telemetry` module: OTel SDK in the CLI, pipeline metrics, spans per step, flush on exit
- [ ] Correlated `run_id`, `dagster_run_id`, `step`, `partition_date` across logs, metrics, spans
- [ ] Four provisioned dashboards (section 11.1)
- [ ] Alert rules and contact point as code; `docs/runbook.md` entry per alert
- [ ] Test: no person data in logs on a fixture run
- [ ] Trigger each alert once on purpose and record that it fired

### Milestone 9: Spring Boot API
- [ ] Spring Boot app in `api/`, depending on the pipeline modules
- [ ] All endpoints from section 13
- [ ] DTO records, validation, paging, ProblemDetail errors
- [ ] Bucket4j rate limit, CORS for Vite dev server
- [ ] Actuator health, OTLP metrics, OTel Java agent traces, springdoc-openapi, Caffeine cache
- [ ] Tests: `@WebMvcTest` for controllers, one `@SpringBootTest` against an Elasticsearch
      Testcontainer

### Milestone 10: React frontend
- [ ] Vite + React + TypeScript app in `web/`
- [ ] TypeScript types generated from the OpenAPI spec
- [ ] All pages from section 14
- [ ] Frontend lint + build in CI
- [ ] `web-dev` compose service with hot reload against the API
- [ ] Spring Boot serves `web/dist` in production; `make up` starts the whole stack with one
      command

### Milestone 11: Polish
- [ ] README: problem, architecture diagram, screenshots (frontend, Dagster asset graph,
      Grafana dashboards), how to run in under 5 commands, design decisions, link to Oversee
      as the reference design
- [ ] ADRs in docs/adr:
      1. Deriving events from snapshot diffs
      2. Deterministic event ids and idempotency
      3. Person matching strategy
      4. Spring Boot only at the API edge, plain Java pipeline
      5. Dagster orchestrating Java steps through Pipes
      6. dbt-duckdb over the lake instead of a warehouse database
      7. Observability stack: OpenTelemetry, Grafana, Prometheus, Loki, Tempo
- [ ] Finish `docs/source-assessment.md` and `docs/runbook.md`
- [ ] Short demo GIF of the frontend

### Stretch
- [ ] Financial statements from VID
- [ ] Deepen Lithuania coverage the same way
- [ ] Cloud deployment: AWS S3 instead of SeaweedFS, managed Elasticsearch, Dagster on a VM or
      Kubernetes, Grafana Cloud
- [ ] Data contracts: schema checks on the UR files that fail `download` when columns change

## 16. Definition of done

- `make build` (Gradle in the `build` container) passes from a clean clone, including the
  ArchUnit boundary test. The host needs only Docker and git.
- `make up` starts the whole stack: lake, Elasticsearch, Dagster, observability, API and
  frontend.
- The Dagster daily schedule runs the full pipeline for a day without manual steps, and the
  asset graph shows every asset and check green.
- `make run DATE=<d>` (`lv-register run --date <d>` in the `cli` container) runs the Java
  steps for a day without Dagster.
- Re-running any day produces no new events (tested).
- `make dbt-build` passes, including the Java/SQL diff parity test.
- The quality report exists for every run and is visible in Dagster, Grafana and the frontend.
- Grafana shows the four dashboards with real data, and each alert has fired once in a test.
- The frontend shows real data for search, company, events, network, pipeline and insights.
- The README explains the source, the model and the trade-offs in under 5 minutes of reading.

## 17. Working rules for you (the agent)

- Start with Milestone 1. Show me the source assessment before writing domain code.
- Run every build, test and tool in a container, never on the host. If a tool is missing, add
  it to an image or a compose service.
- After each milestone: run `make build` (plus `make dbt-build` from Milestone 6 and the
  frontend build from Milestone 10 on), summarize what changed, and tick the checkboxes in
  this file.
- Small commits with clear messages.
- Never commit raw data, lake files, marts or person-level data.
- Never add Spring dependencies outside `modules/api`.
- Keep domain logic in Java. Python in `orchestration/` only wires and checks; dbt models
  stay SQL over the lake.
- If the real data contradicts this plan, tell me and propose a change. Do not silently work
  around it.
- Prefer simple code over clever code. If a library choice is unclear, ask me.
