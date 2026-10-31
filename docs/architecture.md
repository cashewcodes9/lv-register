# Architecture

lv-register turns the daily open data of the Latvian Register of Enterprises (Uzņēmumu
reģistrs, UR) into typed company snapshots and a feed of change events, then serves them
through a read-only API and a React app. It follows the layer design of
[Oversee](https://github.com/cashewcodes9/Oversee), rewritten in Java 25. The scope and
milestones are in [PLAN.md](../PLAN.md).

Column names and value sets are not fixed here. They come from
[source-assessment.md](source-assessment.md) (Milestone 1).

## Layers

```
UR CSVs ─► raw/ ─────────► lake/ (Parquet) ─► normalize ─► Snapshot ─► diff ─► events/ (JSONL)
data.gov.lv  cache by day,  one file per source  pure          typed       A vs B     one file per day
             sha256         file and day, all    functions,    records
             manifest       columns, unfiltered  drop counts                │
                                                                            ▼
                                       quality ─► reports/ (JSON) ─► Elasticsearch ─► API ─► React
                                                                     companies,       /api   web/
                                                                     events, runs
```

| Layer | Where | Holds | Written by |
|---|---|---|---|
| Download cache | `data/raw/YYYY-MM-DD/` | UR files as published, plus `manifest.json` (name, sha256, size, URL) | `ingest` |
| Lake | `data/lake/lv/<file>/date=YYYY-MM-DD/<file>.parquet` | every source row and column, typed by DuckDB, source sha256 in key-value metadata | `lake` |
| Snapshot | in memory | `Snapshot` record for one day: companies and roles, keyed by registration number | `normalize` |
| Events | `data/events/YYYY-MM-DD.jsonl` | events from `diff(snapshot[d-1], snapshot[d])`, sorted by event id | `diff` |
| Reports | `data/reports/YYYY-MM-DD.json` | run record and quality report | `quality` |
| Serving | Elasticsearch indices `companies`, `events`, `runs` | latest company state, all events, all run reports | `index` |

Everything under `data/` is git-ignored. It contains personal data (officers, owners).

**Lake layout:** one Hive-partitioned table per source file. DuckDB reads all days of a file
as one table:

```sql
SELECT date, count(*) FROM read_parquet('data/lake/lv/register/*/*.parquet', hive_partitioning = true)
GROUP BY date;
```

The lake stays unfiltered, so a new column or rule means re-reading Parquet, not
downloading again. Past days cannot be downloaded again: UR publishes only the current state.
The lake is therefore the only history the project has.

## Pipeline

Each step reads files and writes files, so it can run alone for one day. The CLI is
`lv-register <step> --date YYYY-MM-DD`; `run` executes all steps in order.

1. **download** (`ingest`): fetch each UR file over `java.net.http.HttpClient` into
   `data/raw/<date>/`, computing sha256 while streaming. A file whose sha256 is already in the
   manifest is not stored again.
2. **lake** (`lake`): DuckDB JDBC converts each CSV to Parquet with `COPY ... TO`. The source
   sha256 and size go into the Parquet key-value metadata. If the day's Parquet file already
   carries the same sha256, the step does nothing.
3. **normalize** (`normalize`): pure functions turn lake rows into domain records. Each
   returns `Cleaned<T>(rows, droppedByRule)`. They:
   - trim, apply Unicode NFC and collapse whitespace
   - validate the registration number format
   - map Latvian legal forms and statuses to North-Data-style families; values without a
     mapping become `Other` and are counted, never dropped silently
   - drop rows that break a rule, counted per rule
   - drop duplicate registration numbers, counted
4. **diff** (`diff`): compare yesterday's snapshot A with today's B, keyed by registration
   number (rules below). Writes `events/<date>.jsonl`.
5. **quality** (`quality`): build the run record and quality report (PLAN.md section 11).
6. **index** (`index`): upsert companies, events and the run report into Elasticsearch.

Every run gets a run id. It is put in the SLF4J MDC, so every log line carries it, and it is
the id of the run report.

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
- `CompanyEvent` is a sealed interface. Code that handles events uses an exhaustive `switch`,
  so a new event type fails compilation until every handler covers it.

## Idempotency

Re-running any step for a day must leave every output unchanged.

| Layer | Mechanism |
|---|---|
| Raw | skip a file whose sha256 is already in the manifest |
| Lake | skip a day whose Parquet metadata has the same source sha256 |
| Events | event id = `sha256(regNumber \| eventType \| snapshotDate \| canonicalPayload)`; file sorted by id, written to a temp file and renamed |
| Elasticsearch | document id = registration number, event id or run id, so writes are upserts |

Canonical payloads are JSON with sorted keys and sorted sets, so the same change always gives
the same bytes and the same id.

## Modules and dependencies

```
            ┌──────────── cli (Picocli, manual wiring) ──────────────┐
            │                                                        │
            ▼                                                        ▼
ingest   lake   normalize   diff   quality   index   features      api (Spring Boot)
   │       │        │         │       │        │        │            │
   └───────┴────────┴─────────┴───┬───┴────────┴────────┘            │
                                  ▼                                  │
                               domain  ◄─────────────────────────────┘
```

| Module | Holds | May depend on |
|---|---|---|
| `domain` | records, sealed types, invariants in compact constructors; no I/O | nothing |
| `ingest` | downloader, sha256 manifest | `domain` |
| `lake` | DuckDB JDBC reader and writer | `domain` |
| `normalize` | row → record functions, legal form and status mapping | `domain` |
| `diff` | `diff(Snapshot, Snapshot)`, event ids | `domain` |
| `quality` | run record, report | `domain` |
| `index` | Elasticsearch upserts and read queries | `domain` |
| `features` | value features as DuckDB queries over the lake | `domain`, `lake` |
| `cli` | one Picocli command per step; `Main` builds every object by hand | pipeline modules |
| `api` | Spring Boot controllers, DTO records, web config | `domain`, `index`, `features` |

Rules, checked by ArchUnit in the build:
- Only `api` imports `org.springframework`.
- `domain` imports nothing outside the JDK.
- No pipeline module depends on `cli` or `api`.

The pipeline is batch code where a framework adds nothing, so it is plain Java. The API
needs rate limiting, CORS, validation, error handling, health checks and OpenAPI, which
Spring Boot provides. ADR 4 records this.

## Domain model

The sketch in PLAN.md section 7 is final only after the source assessment. Fixed rules:

- Records only, no setters, no `null`: optional values are `Optional<T>`.
- Variants are sealed interfaces: `LegalForm`, `CompanyStatus`, `RoleType`, `CompanyEvent`.
- Invariants live in compact constructors, for example a valid `RegNumber` format.
- `Snapshot` is the full state of one day. `diff` is a pure function of two snapshots.

**Persons:** the open data may not give a stable person id. `PersonKey` is built from what
the data offers, documented in the source assessment and ADR 3. Matching is conservative: a
false merge of two people is worse than a missed match.

## Serving

**Elasticsearch** (single node via Docker Compose locally):

| Index | Document id | Used for |
|---|---|---|
| `companies` | registration number | search, company page, stats |
| `events` | event id | timelines, changes feed |
| `runs` | run id | pipeline page |

**API** (`api`, read-only, under `/api`): the endpoints in PLAN.md section 13.
- DTO records separate from domain records.
- Paging on list endpoints, validated query parameters.
- Errors as RFC 7807 `ProblemDetail` from one `@RestControllerAdvice`.
- Bucket4j rate limit per IP; Caffeine cache for stats and search.
- CORS only for the Vite dev server.
- Actuator health and springdoc OpenAPI. The OpenAPI spec generates the frontend's
  TypeScript types.
- Persons appear only inside one company's response (officers, owners, network). No endpoint
  lists persons.

**Frontend** (`web/`, React + TypeScript + Vite): Overview, Search, Company, Network,
Changes, Pipeline and Insights pages. It talks only to the API, through TanStack Query and
the generated types. In production Spring Boot serves `web/dist`, so API and frontend are one
service.

## Quality and testing

| Test | Checks |
|---|---|
| Unit (JUnit 5, AssertJ) | each normalizer rule and mapping, each record invariant |
| Property (jqwik) | `diff(A, A)` is empty; applying `diff(A, B)` to A gives B for tracked fields; event ids are stable and unique per day |
| Idempotency | running a day twice gives byte-identical output files |
| Integration (Testcontainers) | indexer upserts and API queries against real Elasticsearch |
| Web (`@WebMvcTest`) | controllers, validation, error format |
| Architecture (ArchUnit) | module boundary rules above |

The quality report flags orphans (roles pointing to unknown companies), duplicates, unmapped
legal forms and statuses, missing required fields, and event counts that spike above a
threshold. A spike usually means a parsing problem, not real change.

## Open questions

- **Spring Boot version:** the plan says Spring Boot 3. 3.5 is the last 3.x line, and its
  open-source support window is around mid-2026 (to verify). Spring Boot 4 is current. To
  decide in Milestone 2.
- **Which UR files are in the MVP:** decided in Milestone 1.
- **Person key:** depends on what the officer and owner files contain.
