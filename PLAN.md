# lv-register: Latvia Company Data Pipeline

You are helping me build a portfolio project for the North Data "Modern Java Developer"
application (Hamburg). Follow this plan milestone by milestone. Ask me before changing scope.
The reasons behind the main decisions are collected in [docs/FAQ.md](docs/FAQ.md).

Goal: show that I can take a country North Data does not cover yet, understand its sources on
my own, and turn them into clean, tested, event-based company data, with a React frontend to
explore and check it. The pipeline is scheduled and checked by an orchestrator, like a
production data platform. Full observability (metrics, traces, dashboards, alerts) comes after
the MVP; see [ROADMAP.md](ROADMAP.md).

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
- UR publishes only the current state. Past states cannot be downloaded again, so the lake is
  the only history this project has. This is a demo, so it captures **once a week**, not
  daily (section 6.2). Changes between two captures show up as one net change.

## 2. Reference design: Oversee

This project re-applies the architecture of my Python project Oversee
(github.com/cashewcodes9/Oversee). Read its README.md, docs/architecture.md and ROADMAP.md
before starting. Keep these patterns:

- Raw files cached per capture, fingerprinted with SHA-256, skipped if already processed.
- S3-compatible object storage (SeaweedFS) for raw files and the lake. The code speaks the S3
  API to the endpoint in `S3_ENDPOINT`, so another S3-compatible store is a configuration change.
- Hive-partitioned Parquet lake: `s3://lv-lake/<file>/date=YYYY-MM-DD/<file>.parquet`,
  unfiltered, with the source SHA-256 in the Parquet key-value metadata. DuckDB reads and
  writes it through `httpfs`.
- Cleaning as pure functions that return (rows, droppedCountsByRule).
- A run record per pipeline run (status, rows read, rows written, error) and a quality log of
  rows dropped per rule. Every log line carries the run id.
- Idempotent writes everywhere: re-running a capture must not create anything new.
- dbt for SQL models, with generic and singular data tests, docs and lineage.
- Dagster for orchestration: software-defined assets, partitions, asset checks,
  backfills, schedules. Dagster calls the same code as the CLI.
- A Makefile and a Docker-only path.

Differences from Oversee:

| Topic | Oversee | lv-register |
|---|---|---|
| Pipeline language | Python (pandas) | Java 25 (the job's language). Python only for Dagster and dbt |
| Derived data | port calls from positions | company events from the diff of two consecutive weekly snapshots (section 8) |
| Warehouse | PostgreSQL + dbt-postgres | DuckDB + dbt-duckdb over the Parquet lake; marts written back to S3 as Parquet. No Postgres for data |
| Serving | PostgreSQL | PostgreSQL (`pg_trgm` + `unaccent` search) behind a `serving` port, so an Elasticsearch adapter can replace it later |
| Dagster | `dagster dev`, schedule stopped | always-on webserver and daemon in Docker, weekly capture schedule that can be switched off and on, Postgres for Dagster's own run storage |
| Monitoring | JSON logs with run id | JSON logs with run id, Dagster asset checks, freshness checks and failure sensor. Metrics, traces, dashboards and alerts on the roadmap |
| Spring | none (FastAPI) | Spring Boot 4 for the API only |

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
| Frameworks | Spring Boot 4 (Spring Framework 7) in the `api` module only. Spring Boot 3.x is out of open-source support. All pipeline modules are plain Java with no Spring imports. Manual wiring in the CLI `Main` |
| CLI | Picocli (one subcommand per pipeline step, `--date` option) |
| HTTP API | Spring Boot 4 web, Bucket4j rate limiting, Actuator, springdoc-openapi (the release line for Spring Boot 4), Caffeine cache, ProblemDetail errors |
| JSON | Jackson 3 (`tools.jackson`, the Spring Boot 4 default), records support; the same version in pipeline modules |
| CSV | parsed only by DuckDB (`read_csv`) in the lake step; no Java CSV library. normalize reads Parquet rows |
| HTTP client | java.net.http.HttpClient |
| Object storage | SeaweedFS (S3 API). Java uses the AWS SDK v2 `S3Client` as the S3 protocol client, with the required `S3_ENDPOINT` override and path-style access; no AWS services |
| Lake | Parquet files written and queried with DuckDB 1.4.x LTS (JDBC, `httpfs` extension). Pinned to 1.4: DuckDB 2.0 binaries need AVX, which the dev VM lacks (source assessment 4.9) |
| Events | JSON Lines per capture in S3 |
| SQL models and tests | dbt-core + dbt-duckdb: staging, intermediate and mart models over the lake, marts written as Parquet to S3 |
| Orchestration | Dagster (webserver, daemon, Postgres run storage) with dagster-dbt and dagster-docker. Java steps run in the `cli` image through Dagster Pipes |
| Serving store | PostgreSQL 18 (one instance, databases `lv_serving` and `dagster`), plain JDBC + HikariCP, Flyway migrations; search with `pg_trgm` + `unaccent`. Access only through the `serving` port (Elasticsearch is a later adapter, see ROADMAP.md) |
| Tests | JUnit 5, AssertJ, jqwik (property tests), Testcontainers (PostgreSQL, SeaweedFS), ArchUnit; pytest for Dagster definitions; dbt data tests |
| Logging | SLF4J + Logback, JSON to stdout, run id, Dagster run id, step and partition date in MDC |
| Observability | Roadmap (ROADMAP.md). MVP: logs, Dagster checks, Actuator health |
| Containers | Docker + Docker Compose. Every build, test and run happens in containers (section 6.1). The host needs only Docker, git and make |
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
1. Download the UR CSV files once a week, with checksums and raw caching in S3.
2. Store each capture as a Parquet snapshot in the lake.
3. Parse and normalize into an immutable domain model.
4. Derive change events by diffing consecutive snapshots.
5. Idempotent processing at every layer.
6. Run record and data quality report per run.
7. dbt models and tests over the lake and events: statistics, value feature, invariants.
8. Dagster orchestration: a partition per capture, weekly schedule with an off/on switch,
   asset checks, retries, reprocessing from the lake.
9. Load companies, events and run reports into the serving store (PostgreSQL).
10. One value feature (section 10).
11. Spring Boot read API and React frontend to explore the data and check pipeline health.
12. Retention, so storage stays bounded on an always-on machine (section 6.2).

### Out of scope
- Financial statements. UR publishes annual-report data itself (`gada-parskatu-finansu-dati`,
  ~500 MB per capture; source assessment section 1). On the roadmap.
- Authentication, user accounts, production deployment.
- Publishing personal data (section 12).
- High availability: every service is a single container.
- Metrics, traces, Grafana dashboards and alerting (ROADMAP.md).

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
                    serving store: PostgreSQL (companies, events, runs, stats, features) ─► Spring Boot API ─► React
```

Every pipeline step is a function from input objects to output objects, so each step can be
re-run alone:

```
lv-register download  --date 2026-11-02
lv-register lake      --date 2026-11-02
lv-register diff      --date 2026-11-02
lv-register quality   --date 2026-11-02
lv-register index     --date 2026-11-02
lv-register prune     --date 2026-11-02     # apply retention (section 6.2)
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
| `postgres` | `postgres:18` (pinned) | serving store (database `lv_serving`) and Dagster storage (database `dagster`), data volume |
| `build` | `eclipse-temurin:25-jdk` | runs the Gradle wrapper: `./gradlew build`, tests. Gradle cache in a named volume |
| `cli` | `Dockerfile` target `cli` (JRE 25 + CLI jar) | runs pipeline steps; launched by Dagster or `docker compose run` |
| `api` | `Dockerfile` target `api` (JRE 25 + Spring Boot jar with `web/dist`) | API and frontend on one port |
| `web-dev` | `node:22` (LTS) | Vite dev server with hot reload; `npm` commands; `node_modules` in a named volume |
| `dagster-code` | `orchestration/Dockerfile` (Python, Dagster, dbt-duckdb, dbt project) | Dagster code location (gRPC); launches `cli` containers through the Docker socket |
| `dagster-webserver` | same image | Dagster UI (:3000): asset graph, runs, backfills, checks |
| `dagster-daemon` | same image | schedules, sensors, run queue, retries |
| `duckdb` | small image with the DuckDB CLI | ad-hoc SQL over the lake (Milestone 1 and debugging) |

- Compose profiles keep the default stack small: `core` (lake, postgres, api),
  `orchestration` (Dagster services), `dev` (web-dev). `make up` starts these long-running
  profiles. One-shot containers (build, cli, duckdb) are in profile `tools` and run only
  with `docker compose run`.
- One multi-stage `Dockerfile` for Java: stage `web` builds the React app with Node, stage
  `jdk` builds the jars with the Gradle wrapper, final stages `cli` and `api` copy the jars
  onto `eclipse-temurin:25-jre`. Production image = target `api`.
- Pin image versions (tag or digest). No `latest`.
- Containers run as a non-root user where the image allows it.
- Every long-running service has a healthcheck; dependants wait with
  `condition: service_healthy`.
- Testcontainers inside the `build` container: mount `/var/run/docker.sock` and set
  `TESTCONTAINERS_HOST_OVERRIDE` so tests reach the containers they start.
- Development credentials (SeaweedFS keys, Postgres password) come from
  `.env` (copied from `.env.example`) and are for local use only.
- A `Makefile` wraps the common commands (`make up`, `make down`, `make build`, `make test`,
  `make run DATE=...`, `make capture-now`, `make capture-off`, `make capture-on`, `make status`,
  `make dbt-build`, `make dbt-docs`, `make dagster`, `make web-dev`,
  `make duckdb`), as in Oversee.
- CI uses the same images, so a green CI means a green local build.

### 6.2 Capture schedule, retention and on/off

Why weekly and how captures are dated: [FAQ](docs/FAQ.md#why-weekly-captures-and-how-are-captures-dated).

This is a demo, so it captures **once a week**, not every day.

**Captures:**
- A capture = one download of every MVP file, keyed by its capture date (`date=YYYY-MM-DD`).
- The Dagster schedule `weekly_capture` runs every Monday at 06:00 Europe/Riga. Day and time
  are configuration, adjusted after Milestone 1 finds when UR publishes.
- `make capture-now` starts a capture at any time, for example to get a second snapshot for
  testing without waiting a week.
- `diff` compares a capture with the **previous capture in the lake**, not with "yesterday"
  or "last week". If captures were skipped, the diff spans the gap, and the report records
  both dates and the gap in days.
- Every event records both snapshot dates (`observedBetween`). The event date is when the
  change was seen, not when it happened. Where the source has its own date (registration,
  termination), that date is kept too.
- Limitation, stated in the README: a change that is undone within one week, or a company
  registered and terminated within one week, is not visible.

**Retention** (all values in `.env`):

| Data | Kept | Why |
|---|---|---|
| Raw CSVs | the latest 2 captures | the lake holds the same rows; the manifest keeps every sha256 |
| Lake snapshots | the latest 12 captures (about 3 months) | `diff` only needs the previous capture; older history lives in the events |
| Events, reports | forever | small: only changes and counts |
| Marts | rebuilt each run | derived |
| Serving (PostgreSQL) | latest company state, all events, all runs | upserts, grows only with events |
| Dagster run history | 90 days | Dagster's run retention setting |

- `prune` runs as the last step of every capture. It never deletes the latest capture, or a
  snapshot whose events have not been written.
- The quality report records the size of each bucket. A Dagster check warns when the total
  is above `STORAGE_WARN_GB` (default 10 GB).
- Rough size: about 480K entities, so expect tens of MB per snapshot as Parquet. Measured in
  Milestone 1 and noted in the source assessment.

**Off and on:**

| Switch | Command | Effect |
|---|---|---|
| Capture | `make capture-off`, `make capture-on` | stops or starts the weekly schedule (stored in Dagster); the stack and UI keep running |
| Whole stack | `make down`, `make up` | stops or starts all containers; data stays in Docker volumes |
| Status | `make status` | containers, schedule state, last capture date, storage use |

- Services use `restart: unless-stopped`, so a running stack comes back after a reboot, and a
  stack stopped with `make down` stays stopped.
- Turning capture back on does not fill the gap. UR has no history, so the next capture
  starts again from the current state, and its diff spans the gap.

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
    storage/      S3 object store for SeaweedFS (AWS SDK v2 as protocol client) and local directory
    ingest/       download, sha256 manifest, raw cache
    lake/         Parquet snapshots via DuckDB JDBC (httpfs)
    normalize/    lake rows -> domain, legal form + status mapping, dropped-row counts
    diff/         snapshot comparison -> events, deterministic event ids
    quality/      run record, quality report
    index/        indexing step: loads companies, events, runs and marts through `ServingWriter`
    serving/      serving port: `ServingWriter`, `ServingQueries`, read records, contract tests
    serving-postgres/  PostgreSQL adapter: plain JDBC, Flyway migrations, `pg_trgm` search
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
  docs/
    source-assessment.md
    architecture.md
    adr/
  .github/workflows/ci.yml
```

Dependency direction: `api` and `cli` depend on the pipeline modules, never the other way
round. `orchestration/` and `transform/` depend on the Java code only through the `cli`
image and the files it writes.

## 7. Domain model (sketch, finalize after Milestone 1)

MVP files (decided after Milestone 1, see `docs/source-assessment.md`): `register`,
`officers`, `members`, `members_joint_owners`, `stockholders`, `stockholders_joint_owners`,
`beneficial_owners`, `equity_capitals`, `liquidations`, `insolvency_legal_person_proceeding`,
`reorganizations`. Status combines `register.closed`, open liquidations and open insolvency
proceedings:

```java
enum CompanyStatus { ACTIVE, IN_LIQUIDATION, INSOLVENT, LEGAL_PROTECTION, TERMINATED, REORGANIZED }
```

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

IMPORTANT: before writing domain code, download one capture of every file, record the real column
names and value sets in `docs/source-assessment.md`, and only then finalize the model. Do not
guess column names.

## 8. Event derivation rules

Compare the previous capture (A) with the current one (B), keyed by registration number:

| Condition | Event |
|---|---|
| In B, not in A | Registered |
| Name differs | NameChanged(old, new) |
| Address differs (after normalization) | AddressChanged(old, new) |
| Legal form differs | LegalFormChanged(old, new) |
| Officer set differs | ManagementChanged(added, removed) |
| Members / stockholders / BO set differs | OwnershipChanged(added, removed) |
| Status differs (e.g. to liquidation) | StatusChanged(old, new) |
| Termination date appears | Terminated(date). Role files list active entities only, so the
  roles of a terminated company vanish in the same capture: no ManagementChanged or
  OwnershipChanged is emitted for it (source assessment 4.2) |
| In A, not in B | MissingFromSnapshot (flag for review, removal is unusual) |

- Normalize before comparing (trim, Unicode NFC, collapse whitespace, consistent case for
  codes) so formatting noise does not create fake events.
- The first snapshot creates no events. It is the baseline.
- The Java diff is the source of truth. A dbt model computes the same diff in SQL, and a dbt
  test requires both to agree (section 9), as Oversee's clean_sql vs pandas test does.

## 9. Idempotency and correctness

- Raw layer: a file already in the capture's manifest (with an object of the recorded size) is
  not downloaded again; the manifest keeps its sha256. A capture is fixed once written.
- Lake: writing the same day again with the same source sha256 is a no-op.
- Event ids are deterministic: `sha256(regNumber | eventType | snapshotDate | canonicalPayload)`.
  Re-running a capture writes the same ids.
- Objects are written to a temporary key and copied into place, so a failed run never leaves
  a half-written object under the final key.
- Serving keys = company reg number, event id or run id, so loading is an upsert
  (`INSERT ... ON CONFLICT DO UPDATE` in PostgreSQL; the document id in a later Elasticsearch adapter).
- dbt marts are full rebuilds from the lake and events, so re-running gives the same result.
- Property tests (jqwik):
  - `diff(A, A)` is always empty.
  - Running the pipeline twice on the same input gives byte-identical output files.
  - Applying the events from `diff(A, B)` to A reproduces B for the tracked fields.
  - Event ids are stable across runs and unique within a capture.
- dbt tests on real data:
  - unique and not-null keys; relationships (roles point to known companies)
  - accepted values for mapped legal forms and statuses
  - singular: the SQL diff equals the Java events for every capture (parity)
  - singular: no events on the baseline capture; event ids unique per capture
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
tests, loaded into the serving store for the API.

## 11. Run record and data quality report

Per run, write `s3://lv-lake/reports/date=YYYY-MM-DD/report.json` with:
- run id (and the Dagster run id when launched by Dagster), start/end time, status, error
- source file names, sha256, sizes
- rows read and rows kept per file, change vs previous capture, days since previous capture
- rows dropped per cleaning rule
- missing required fields per column
- officers or owners pointing to unknown companies (orphans)
- duplicate registration numbers
- unmapped legal forms or statuses
- events per type, with a warning when a type spikes beyond a threshold (a spike usually
  means a parsing problem, not real change)

The same figures go to two places:
- Dagster: materialization metadata and asset checks (via Pipes), so a bad day is visible in
  the asset graph and can block downstream assets.
- The serving store (`runs` table), so the API and frontend show pipeline health.

### 11.1 Monitoring in the MVP

Full observability is on the roadmap (ROADMAP.md). The MVP keeps what is needed to notice a
missed or broken capture:
- JSON logs to stdout with `run_id`, `dagster_run_id`, `step` and `partition_date` in every
  line, readable with `docker compose logs` and in the Dagster run view.
- Dagster asset checks (warn or block), freshness checks on the weekly assets, a run failure
  sensor (logs, plus an optional webhook URL in `.env`), and retry policies with backoff on
  network steps.
- Actuator health for the API, with a database indicator; healthchecks on every service.
- The frontend Pipeline page for run history and quality reports.

## 12. Personal data

Officers and beneficial owners are personal data.
- The public demo shows company-level data and aggregates only.
- The API returns persons only in the context of one company (its officers and owners, and
  its network graph). No endpoint lists or exports persons in bulk.
- Raw data, the lake and the marts are never committed. Tests use small, anonymized fixtures.
- Logs and Dagster metadata never contain person names or person keys. They carry
  registration numbers and counts only. A test checks the CLI log output on a fixture.
- Dagster, SeaweedFS and PostgreSQL are bound to localhost and not exposed.
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
| `GET /api/stats` | Counts per legal form, status and region; events per capture |
| `GET /api/runs`, `GET /api/runs/{id}` | Pipeline runs and quality reports |
| `GET /api/features/mass-addresses` | Value feature results |

- DTOs as records, separate from domain records.
- Validation on query params, paging on list endpoints.
- Errors as RFC 7807 ProblemDetail via `@RestControllerAdvice`.
- Bucket4j rate limit per IP, simple configurable default.
- CORS allowed for the Vite dev server only.
- Actuator health (with database indicator), springdoc-openapi (Swagger UI). Generate TypeScript types for the frontend from the OpenAPI
  spec.
- Caffeine cache for stats and search.

## 14. Frontend (React)

Pages:
1. **Overview:** headline stats (companies by legal form and status, events per capture).
2. **Search:** company search with a results list.
3. **Company:** base data, officers and owners, event timeline.
4. **Network:** company-person graph around one company.
5. **Changes:** events per capture, filter by type.
6. **Pipeline:** runs and quality reports (rows read/kept, dropped per rule, event spikes),
   with a link to the Dagster run.
   This is the "check what we have" view.
7. **Insights:** value feature results, e.g. mass-registration addresses.

Rules:
- Data fetching with TanStack Query only, against the generated API types.
- No mock data once the API exists. Build pages against real API responses.
- Keep styling simple and clean. Function over decoration.

## 15. Milestones

### Milestone 1: Source exploration
- [x] Minimal `docker-compose.yml` with the `duckdb` service for exploring the files; no
      tools installed on the host
- [x] Download one capture of every UR file
- [x] Record columns, types, value sets, row counts, encoding, delimiters, quirks in
      `docs/source-assessment.md`
- [x] Find out when UR publishes updates (sets the weekly schedule time)
- [x] Measure file sizes as CSV and as Parquet (sets the retention defaults)
- [x] Map each field to a North Data feature (base data, history, representatives,
      shareholders, person networks, segment codes)
- [x] Decide which files are in MVP
- [x] STOP and show me the assessment before continuing

### Milestone 2: Project setup + domain

- [x] Gradle multi-module, Java 25 toolchain
- [x] Multi-stage `Dockerfile`; compose services `lake`, `postgres`, `build`, `cli`,
      `duckdb` with healthchecks and profiles; `.env.example`; `Makefile`
- [x] `make build` runs the Gradle build in the `build` container from a clean clone
- [x] GitHub Actions CI (build + test)
- [x] `domain` module: records and sealed types, one invariant test per record
- [x] Picocli skeleton with all subcommands, Main with manual wiring
- [x] ArchUnit test: no module except `api` imports `org.springframework`
- [x] Logback JSON logging with run id in MDC

### Milestone 3: Storage + ingest + lake + normalize
- [x] `storage` module: S3 object store against SeaweedFS (Testcontainers) and local directory
- [x] Downloader with sha256 manifest in `s3://lv-raw`, skip if unchanged, write via temp key
- [x] Lake writer: CSV to Parquet via DuckDB `httpfs`, source sha in metadata
- [x] Normalizers per file returning `Cleaned<T>`
- [x] Legal form and status mapping with tests

### Milestone 4: Diff + events
- [x] Snapshot diff for all event types in section 8
- [x] Deterministic event ids
- [x] Property tests from section 9
- [ ] Collect two or more real captures (`make capture-now` a few days apart) and produce real
      events (so far: 2026-10-31 and 2026-11-01, one day apart, 4 real events; open until a
      capture a few days later)
- [ ] Spot-check 5 real events against info.ur.gov.lv (open: needs more captures; the portal
      needs a browser)

### Milestone 5: Quality + index
- [x] Run record and quality report (section 11)
- [x] `serving` port (`ServingWriter`, `ServingQueries`) with a contract test suite;
      `serving-postgres`: Flyway schema for companies, events and runs (`pg_trgm`, `unaccent`)
- [x] Indexer with upserts through the port; Postgres adapter passes the contract tests (Testcontainers)
- [x] Testcontainers working from the `build` container (done in Milestone 3)
- [x] Test: no person data in CLI logs on a fixture run

### Milestone 6: dbt
Why dbt, and what marts are: [FAQ](docs/FAQ.md#what-does-dbt-add).

- [ ] dbt project in `transform/` with dbt-duckdb reading the lake and events from S3
- [ ] Sources with tests; staging and intermediate models per MVP file
- [ ] Marts: `dim_companies`, `fct_company_events`, stats aggregates, value feature
      (section 10), written as Parquet to `s3://lv-marts`
- [ ] SQL diff model and the parity test against the Java events (section 9)
- [ ] Singular tests from section 9; exposures for the API and frontend
- [ ] `make dbt-build`, `make dbt-docs` (lineage graph)
- [ ] Indexer loads stats and feature marts into the serving store

### Milestone 7: Dagster orchestration
Why Dagster, and its cost: [FAQ](docs/FAQ.md#what-does-dagster-add).

- [ ] `orchestration/` Python project and image; `dagster` database in the `postgres` service;
      compose services `dagster-code`, `dagster-webserver`, `dagster-daemon`
- [ ] `pipes` module: the CLI reports materializations, metadata and check results to Dagster
- [ ] Assets with one partition per capture date: `ur_raw_files` ─► `lake_snapshot` ─►
      `company_events` (reads the previous capture from the lake) ─► `quality_report` ─►
      dbt models ─► `search_index` ─► `retention`
- [ ] Java steps launched in the `cli` image through Dagster Pipes; dbt via dagster-dbt with
      dbt tests as asset checks
- [ ] Asset checks from the quality report (drop share, orphans, unmapped values, event
      spikes, storage size); blocking checks stop downstream assets
- [ ] `weekly_capture` schedule (day and time from Milestone 1), freshness check, run failure
      sensor, retry policy with backoff for `download`
- [ ] `make capture-now`, `make capture-off`, `make capture-on`, `make status` (section 6.2)
- [ ] `prune` step and retention settings in `.env`; Dagster run retention 90 days
- [ ] Reprocessing past captures from the lake (not from UR) works from the UI
- [ ] pytest: definitions load, asset graph and partition mapping are as expected

### Milestone 8: Spring Boot API
- [ ] Spring Boot 4 app in `api/`; reads only through `ServingQueries` (adapter chosen by `SERVING_BACKEND`)
- [ ] All endpoints from section 13
- [ ] DTO records, validation, paging, ProblemDetail errors
- [ ] Bucket4j rate limit, CORS for Vite dev server
- [ ] Actuator health, springdoc-openapi, Caffeine cache
- [ ] Tests: `@WebMvcTest` for controllers, one `@SpringBootTest` against a PostgreSQL
      Testcontainer

### Milestone 9: React frontend
- [ ] Vite + React + TypeScript app in `web/`
- [ ] TypeScript types generated from the OpenAPI spec
- [ ] All pages from section 14
- [ ] Frontend lint + build in CI
- [ ] `web-dev` compose service with hot reload against the API
- [ ] Spring Boot serves `web/dist` in production; `make up` starts the whole stack with one
      command

### Milestone 10: Polish
- [ ] README: problem, architecture diagram, screenshots (frontend, Dagster asset graph), how
      to run in under 5 commands, design decisions, link to Oversee as the reference design
- [ ] ADRs in docs/adr:
      1. Deriving events from snapshot diffs
      2. Deterministic event ids and idempotency
      3. Person matching strategy
      4. Spring Boot 4 only at the API edge, plain Java pipeline
      5. Dagster orchestrating Java steps through Pipes
      6. dbt-duckdb over the lake instead of a warehouse database
      7. PostgreSQL as the serving store behind a port, Elasticsearch as a later adapter
- [ ] Finish `docs/source-assessment.md`
- [ ] Short demo GIF of the frontend

After the MVP: [ROADMAP.md](ROADMAP.md) (observability first, then the stretch items there).

## 16. Definition of done

- `make build` (Gradle in the `build` container) passes from a clean clone, including the
  ArchUnit boundary test. The host needs only Docker, git and make.
- `make up` starts the whole stack: lake, PostgreSQL, Dagster, API and frontend.
- The Dagster weekly schedule runs the full pipeline for a capture without manual steps, and the
  asset graph shows every asset and check green.
- `make run DATE=<d>` (`lv-register run --date <d>` in the `cli` container) runs the Java
  steps for a capture without Dagster.
- Re-running any capture produces no new events (tested).
- `make capture-off` and `make capture-on` switch the schedule; `make down` and `make up` stop
  and start the stack without losing data.
- Retention keeps storage under the configured limit (tested on fixtures).
- `make dbt-build` passes, including the Java/SQL diff parity test.
- The quality report exists for every run and is visible in Dagster and the frontend.
- The frontend shows real data for search, company, events, network, pipeline and insights.
- The README explains the source, the model and the trade-offs in under 5 minutes of reading.

## 17. Working rules for you (the agent)

- Start with Milestone 1. Show me the source assessment before writing domain code.
- Run every build, test and tool in a container, never on the host. If a tool is missing, add
  it to an image or a compose service.
- Keep [EXECUTION.md](EXECUTION.md) current: update it and the PLAN.md checkboxes at the end
  of every work session, before a restart, and after every milestone.
- After each milestone: run `make build` (plus `make dbt-build` from Milestone 6 and the
  frontend build from Milestone 9 on), summarize what changed, and tick the checkboxes in
  this file.
- Small commits with clear messages.
- Never commit raw data, lake files, marts or person-level data.
- Never add Spring dependencies outside `modules/api`.
- Keep domain logic in Java. Python in `orchestration/` only wires and checks; dbt models
  stay SQL over the lake.
- If the real data contradicts this plan, tell me and propose a change. Do not silently work
  around it.
- Prefer simple code over clever code. If a library choice is unclear, ask me.
