# lv-register: Latvia Company Data Pipeline

You are helping me build a portfolio project for the North Data "Modern Java Developer"
application (Hamburg). Follow this plan milestone by milestone. Ask me before changing scope.

Goal: show that I can take a country North Data does not cover yet, understand its sources on
my own, and turn them into clean, tested, event-based company data, with a React frontend to
explore and check it.

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

## 2. Reference design: Oversee

This project re-applies the architecture of my Python project Oversee
(github.com/cashewcodes9/Oversee) in Java 25. Read its README.md and docs/architecture.md
before starting. Keep these patterns:

- Raw files cached by day, fingerprinted with SHA-256, skipped if already processed.
- Hive-partitioned Parquet lake: `lake/lv/date=YYYY-MM-DD/<file>.parquet`, unfiltered, with
  the source SHA-256 in the Parquet key-value metadata. Use DuckDB through its JDBC driver.
- Cleaning as pure functions that return (rows, droppedCountsByRule).
- A run record per pipeline run (status, rows read, rows written, error) and a quality log of
  rows dropped per rule. Every log line carries the run id.
- Idempotent writes everywhere: re-running a day must not create anything new.
- Invariant tests on derived data, like Oversee's dbt singular tests.

Difference: Oversee derives port calls from positions. Here we derive company events from the
diff of two daily snapshots (section 8). No Postgres or dbt. Elasticsearch is the serving
store.

## 3. Language and style

Java 25, matching North Data's backend (modern Java, functional style).
- Immutable records, sealed interfaces, pattern matching for switch, record patterns,
  streams, Optional.
- Pure functions for all transformation logic. I/O only at the edges.
- No setters, no nulls in the domain model (use Optional), no inheritance hierarchies
  beyond sealed types.

## 4. Tech stack

| Area | Choice |
|---|---|
| Language | Java 25 |
| Build | Gradle (Kotlin DSL), toolchain set to 25 |
| Frameworks | Spring Boot 3 in the `api` module only. All pipeline modules are plain Java with no Spring imports. Manual wiring in the CLI `Main` |
| CLI | Picocli (one subcommand per pipeline step, `--date` option) |
| HTTP API | Spring Boot web, Bucket4j rate limiting, Actuator, springdoc-openapi, Caffeine cache, ProblemDetail errors |
| JSON | Jackson (with records support) |
| CSV | FastCSV or Apache Commons CSV |
| HTTP client | java.net.http.HttpClient |
| Lake | Parquet files written and queried with DuckDB (JDBC) |
| Events | JSON Lines per day |
| Search | Elasticsearch 8 (Docker Compose locally) |
| Tests | JUnit 5, AssertJ, jqwik (property tests), Testcontainers (Elasticsearch), ArchUnit |
| Logging | SLF4J + Logback, run id in MDC |
| CI | GitHub Actions: backend build + test, frontend lint + build |
| Frontend | React + TypeScript (Vite), TanStack Query for data fetching, Recharts for charts, react-force-graph or d3.js for the company-person network. Talks only to the API. In production, Spring Boot serves the built React app as static files, so it stays one service |

Why this split: the pipeline is batch code where a framework adds nothing, so it stays plain
Java and runs via Picocli. The React-facing API needs middleware (rate limiting, CORS,
validation, error handling, health, OpenAPI), so it uses Spring Boot, where these are
standard. Spring stays at the edge: domain and pipeline modules must not depend on it.
Document this in an ADR.

## 5. Scope

### In scope (MVP)
1. Download the daily UR CSV files, with checksums and raw caching.
2. Store each day as a Parquet snapshot in the lake.
3. Parse and normalize into an immutable domain model.
4. Derive change events by diffing consecutive snapshots.
5. Idempotent processing at every layer.
6. Run record and data quality report per run.
7. Index companies and events into Elasticsearch.
8. One value feature (section 10).
9. Spring Boot read API and React frontend to explore the data and check pipeline health.

### Out of scope
- Financial statements (filed with the State Revenue Service, VID). Stretch goal only.
- Authentication, user accounts, production deployment.
- Publishing personal data (section 12).

## 6. Architecture

```
UR CSVs ─► raw/YYYY-MM-DD/*.csv ─► lake/lv/date=YYYY-MM-DD/*.parquet ─► normalize ─► snapshot
           (cache, sha256           (DuckDB, unfiltered,                (pure        (typed
            manifest)                source sha in metadata)             functions)   records)
                                                                                        │
                       diff(snapshot[d-1], snapshot[d]) ─► events/YYYY-MM-DD.jsonl ◄────┘
                                                                │
                              quality ─► reports/YYYY-MM-DD.json│
                                                                ▼
                       Elasticsearch (companies, events) ─► Spring Boot API (/api) ─► React app
```

Every pipeline step is a function from input files to output files, so each step can be
re-run alone:

```
lv-register download  --date 2026-11-02
lv-register lake      --date 2026-11-02
lv-register diff      --date 2026-11-02
lv-register quality   --date 2026-11-02
lv-register index     --date 2026-11-02
lv-register run       --date 2026-11-02     # all steps
```

The API runs separately: `./gradlew :modules:api:bootRun`. The CLI never boots Spring.

### Module layout

```
lv-register/
  build.gradle.kts
  settings.gradle.kts
  docker-compose.yml          elasticsearch
  modules/
    domain/       records, sealed types, no I/O, no dependencies
    ingest/       download, sha256 manifest, raw cache
    lake/         Parquet snapshots via DuckDB JDBC
    normalize/    CSV/Parquet rows -> domain, legal form + status mapping, dropped-row counts
    diff/         snapshot comparison -> events, deterministic event ids
    quality/      run record, quality report
    index/        Elasticsearch writer (upserts) and read queries
    features/     value feature(s), section 10
    cli/          Picocli commands, Main with manual wiring
    api/          Spring Boot read API under /api: controllers, DTO records, Bucket4j rate limit,
                  CORS for Vite dev server, Actuator, OpenAPI, serves web/dist in production
  web/            React + TypeScript app (Vite)
  docs/
    source-assessment.md
    adr/
  .github/workflows/ci.yml
```

Dependency direction: `api` and `cli` depend on the pipeline modules, never the other way
round.

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

## 9. Idempotency and correctness

- Raw layer: a file with the same sha256 as one already stored is skipped.
- Lake: writing the same day again with the same source sha256 is a no-op.
- Event ids are deterministic: `sha256(regNumber | eventType | snapshotDate | canonicalPayload)`.
  Re-running a day writes the same ids.
- Elasticsearch document id = company reg number or event id, so indexing is an upsert.
- Property tests (jqwik):
  - `diff(A, A)` is always empty.
  - Running the pipeline twice on the same input gives byte-identical output files.
  - Applying the events from `diff(A, B)` to A reproduces B for the tracked fields.
  - Event ids are stable across runs and unique within a day.
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

Default choice: feature 1, computed as a DuckDB query over the lake.

## 11. Run record and data quality report

Per run, write `reports/YYYY-MM-DD.json` with:
- run id, start/end time, status, error (if any)
- source file names, sha256, sizes
- rows read and rows kept per file, change vs previous day
- rows dropped per cleaning rule
- missing required fields per column
- officers or owners pointing to unknown companies (orphans)
- duplicate registration numbers
- unmapped legal forms or statuses
- events per type, with a warning when a type spikes beyond a threshold (a spike usually
  means a parsing problem, not real change)

Reports are also indexed so the API and frontend can show pipeline health.

## 12. Personal data

Officers and beneficial owners are personal data.
- The public demo shows company-level data and aggregates only.
- The API returns persons only in the context of one company (its officers and owners, and
  its network graph). No endpoint lists or exports persons in bulk.
- Raw data and the lake are git-ignored. Tests use small, anonymized fixtures.
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
- Actuator health, springdoc-openapi (Swagger UI). Generate TypeScript types for the frontend
  from the OpenAPI spec.
- Caffeine cache for stats and search.

## 14. Frontend (React)

Pages:
1. **Overview:** headline stats (companies by legal form and status, events per day).
2. **Search:** company search with a results list.
3. **Company:** base data, officers and owners, event timeline.
4. **Network:** company-person graph around one company.
5. **Changes:** events per day, filter by type.
6. **Pipeline:** runs and quality reports (rows read/kept, dropped per rule, event spikes).
   This is the "check what we have" view.
7. **Insights:** value feature results, e.g. mass-registration addresses.

Rules:
- Data fetching with TanStack Query only, against the generated API types.
- No mock data once the API exists. Build pages against real API responses.
- Keep styling simple and clean. Function over decoration.

## 15. Milestones

### Milestone 1: Source exploration
- [ ] Download one day of every UR file
- [ ] Record columns, types, value sets, row counts, encoding, delimiters, quirks in
      `docs/source-assessment.md`
- [ ] Map each field to a North Data feature (base data, history, representatives,
      shareholders, person networks, segment codes)
- [ ] Decide which files are in MVP
- [ ] STOP and show me the assessment before continuing

### Milestone 2: Project setup + domain
- [ ] Gradle multi-module, Java 25 toolchain
- [ ] GitHub Actions CI (build + test)
- [ ] `domain` module: records and sealed types, one invariant test per record
- [ ] Picocli skeleton with all subcommands, Main with manual wiring
- [ ] ArchUnit test: no module except `api` imports `org.springframework`

### Milestone 3: Ingest + lake + normalize
- [ ] Downloader with sha256 manifest, skip if unchanged
- [ ] Lake writer: CSV to Parquet via DuckDB, source sha in metadata
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
- [ ] docker-compose.yml for local Elasticsearch

### Milestone 6: Value feature + Spring Boot API
- [ ] Value feature from section 10
- [ ] Spring Boot app in `api/`, depending on the pipeline modules
- [ ] All endpoints from section 13
- [ ] DTO records, validation, paging, ProblemDetail errors
- [ ] Bucket4j rate limit, CORS for Vite dev server
- [ ] Actuator health, springdoc-openapi, Caffeine cache
- [ ] Tests: `@WebMvcTest` for controllers, one `@SpringBootTest` against an Elasticsearch
      Testcontainer

### Milestone 7: React frontend
- [ ] Vite + React + TypeScript app in `web/`
- [ ] TypeScript types generated from the OpenAPI spec
- [ ] All pages from section 14
- [ ] Frontend lint + build in CI
- [ ] Spring Boot serves `web/dist` in production; one command starts API + frontend

### Milestone 8: Polish
- [ ] README: problem, architecture diagram, screenshots, how to run in under 5 commands,
      design decisions, link to Oversee as the reference design
- [ ] ADRs in docs/adr:
      1. Deriving events from snapshot diffs
      2. Deterministic event ids and idempotency
      3. Person matching strategy
      4. Spring Boot only at the API edge, plain Java pipeline
- [ ] Finish `docs/source-assessment.md`
- [ ] Short demo GIF of the frontend

### Stretch
- [ ] Financial statements from VID
- [ ] Deepen Lithuania coverage the same way
- [ ] Scheduled daily run (GitHub Actions cron or GCP Cloud Run job)
- [ ] DuckDB SQL version of the diff, with a parity test against the Java diff (like Oversee's
      clean_sql vs pandas test)

## 16. Definition of done

- `./gradlew build` passes from a clean clone, including the ArchUnit boundary test.
- `lv-register run --date <d>` runs the full pipeline for a day.
- Re-running any day produces no new events (tested).
- The quality report exists for every run and is visible in the frontend.
- The frontend shows real data for search, company, events, network, pipeline and insights.
- The README explains the source, the model and the trade-offs in under 5 minutes of reading.

## 17. Working rules for you (the agent)

- Start with Milestone 1. Show me the source assessment before writing domain code.
- After each milestone: run `./gradlew build` (and the frontend build from Milestone 7 on),
  summarize what changed, and tick the checkboxes in this file.
- Small commits with clear messages.
- Never commit raw data, lake files or person-level data.
- Never add Spring dependencies outside `modules/api`.
- If the real data contradicts this plan, tell me and propose a change. Do not silently work
  around it.
- Prefer simple code over clever code. If a library choice is unclear, ask me.
