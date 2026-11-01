# Execution log

Where the work stands, so a new session can continue without re-deriving context. Updated at
the end of every work session and every milestone (see CLAUDE.md). The plan itself is
[PLAN.md](PLAN.md); decisions about the source are in
[docs/source-assessment.md](docs/source-assessment.md).

**Last updated:** 2026-11-01 · **Current milestone:** 10 (not started); 4 has two open real-data items

## Status

| Milestone | State |
|---|---|
| 1. Source exploration | done, approved by owner 2026-10-31 |
| 2. Project setup + domain | done 2026-10-31: `make build` green from a clean clone (3m 35s, cold cache) |
| 3. Storage + ingest + lake + normalize | done 2026-10-31, merged into `dev` (PR #1, CI green) |
| 4. Diff + events | code merged into `dev` 2026-11-01 (PR #2, CI green); open: a capture a few days later, spot-check 5 events |
| 5. Quality + index | done 2026-11-01, merged into `dev` (PR #3, CI green) |
| 6. dbt | done 2026-11-01, merged into `dev` (PR #4, CI green) |
| 7. Dagster orchestration | done 2026-11-01, merged into `dev` (PR #5, CI green) |
| 8. Spring Boot API | done 2026-11-01, merged into `dev` (PR #6, CI green) |
| 9. React frontend | done 2026-11-01, merged into `dev` (PR #7, CI green) |
| 10 | not started |

Git: Milestones 1 and 2 are pushed to `main`, and CI is green (runs 41231743537, 41232519585).
`dev` was branched from `main` on 2026-10-31 and is the working branch from then on. Branch
flow (CLAUDE.md): `milestone/<n>-<name>` from `dev` → PR into `dev` → merge after green CI
(merge commit, done by Claude) → `dev` to `main` only when the owner asks. CI runs on pushes
to `main` and `dev` and on PRs into either. The `gh` token now has the `workflow` scope.

## What exists (Milestone 2)

- Gradle 9.8.2 multi-module build: `settings.gradle.kts` with modules `domain storage ingest lake
  normalize diff quality index serving serving-postgres pipes cli` under `modules/` (`api` joins
  in Milestone 8). `serving` (port, `java-test-fixtures` for the contract suite) and
  `serving-postgres` (adapter; driver, HikariCP, Flyway declared) have no classes yet.
  Version catalog `gradle/libs.versions.toml`; convention plugin `lv.java-conventions` in
  `buildSrc/` (Java 25 toolchain, `-Xlint:all -Werror`, JUnit 6, AssertJ).
- `modules/domain`: `RegNumber`, `LegalForm` + `LegalFamily`, `CompanyStatus`, `Address`,
  `Money`, `Company`, `PersonKey`, `Party` (sealed), `Role` (sealed), `Snapshot`,
  `ObservedBetween`, `CompanyEvent` (sealed, 9 types), `Cleaned`, `Checks`. 25 tests.
- `modules/cli`: Picocli `Main` (download, lake, diff, quality, index, prune, run; `--date`),
  `Steps` wiring with `notImplemented()` placeholders, `StepRunner` (MDC, logs, exit code),
  `RunContext`, `logback.xml` (LogstashEncoder JSON). Tests: `MainTest`, `RunContextTest`,
  `JsonLoggingTest` (encodes events with the configured STDOUT encoder), `ArchitectureTest`
  (no Spring outside `..api..`; `domain` depends only on `java..`; the `serving` port has no
  SQL/JDBC/search types; only `cli`, `api` and adapters depend on `serving.*` adapter
  packages). The two serving rules use `allowEmptyShould` until Milestone 5 adds classes:
  then check that they really match (e.g. break one on purpose). 18 tests.
- `docker/builder/Dockerfile`: build image (JDK 25, user with host uid/gid, `GRADLE_USER_HOME`
  on the `gradle-cache` named volume, entrypoint `./gradlew`). Named `builder`, not `build`,
  because `.gitignore` ignores `build/`.
- Root `Dockerfile`: stages `jdk` (`./gradlew :cli:installDist`, BuildKit cache mount) and `cli`
  (JRE 25, user uid 10001). `.dockerignore` keeps `data/` and build output out.
- `docker-compose.yml`: profile `core` = `lake` (SeaweedFS 4.51, S3 :8333, filer :8888, keys in
  `docker/seaweedfs/s3.json`), `lake-init` (one-shot, `docker/seaweedfs/init-buckets.sh`,
  idempotent), `postgres` (18.6-alpine3.24, :5432; `docker/postgres/init-databases.sh` creates
  databases `lv_serving` and `dagster` with owner roles of the same name on first start;
  passwords from `.env`; CONNECT revoked from PUBLIC); profile
  `tools` = `build`, `cli`, `duckdb`. Ports on 127.0.0.1, healthchecks, named volumes
  `lake-data`, `pg-data`, `gradle-cache`, optional `.env`.
- `.env.example`, `Makefile` (`help up down build test run status duckdb fetch-capture`),
  `.github/workflows/ci.yml` (`make build` + `docker build --target cli`).
- Checked by hand: S3 write and read through DuckDB `httpfs` with the dev keys; a wrong secret
  gets HTTP 403; running `lake-init` a second time works. Postgres: the `lv_serving` role
  can create `pg_trgm` and `unaccent` (trusted extensions, so Flyway can own them);
  `unaccent('Rīga SIA Ķekava')` = `Riga SIA Kekava`; `lv_serving` cannot connect to `dagster`.

## What exists (Milestone 3)

- `storage`: `ObjectStore` (put, open/read, size, copy, delete, list, `putAtomically` via
  `_tmp/<uuid>/` + copy, `location()` for DuckDB), `S3ObjectStore` (AWS SDK v2 as S3 client
  for SeaweedFS: endpoint required, path style, `url-connection-client`, checksums
  `WHEN_REQUIRED`), `LocalDirectoryObjectStore`, `S3Settings` (`S3_ENDPOINT` required),
  `Keys` (safe keys). One contract suite runs on both stores; test fixture `SeaweedFs` starts
  `chrislusf/seaweedfs:4.51` once per JVM.
- `ingest`: `SourceFile` (the 11 MVP files), `CkanCatalog` (CKAN `package_search`, files by
  name, missing/duplicate fails), `Http` (retries 5xx and I/O, 4xx fails), `Downloader`
  (streaming sha256, `putAtomically`, manifest rewritten after each file, skip files already
  in the capture's manifest), `Manifest`/`ManifestEntry` (Jackson 3), `RawLayout`.
- `lake`: `DuckDb` (limits, httpfs, SeaweedFS secret), `DuckDbSettings` (`DUCKDB_*` env),
  `LakeWriter` (all-VARCHAR `read_csv`, Parquet ZSTD written locally then uploaded, KV metadata
  `source_file/source_sha256/source_size/capture_date`, skip when sha matches), `LakeReader`
  (stream of rows as maps, NULLs left out, `hive_partitioning=false`), `LakeLayout`.
- `normalize`: `Text`, `Row`, `Outcome` (sealed Kept/Dropped), `Cleaning`, `LegalForms` (all
  54 codes), `Statuses`, `OpenProceedings`, `PersonKeys`, `Parties`, `RegisterNormalizer`,
  `StatusNormalizers` (liquidations, proceedings, equity, reorganizations, capital choice),
  `RoleNormalizers` (officers, members/stockholders with joint owners and depositories, BOs),
  `Snapshots.assemble`. Real-capture results: docs/source-assessment.md section 9.
- Domain changes: `Cleaned` has `flaggedByRule`; `BeneficialOwner.nationalities` is a set;
  `Role.Officer` may have neither position nor governing body; decimals canonical (`100`, not
  `1E+2`).
- `cli`: `Wiring.production(env)` wires `download` and `lake`; the other steps are still
  placeholders. `RealCaptureCheck` (skipped unless `LV_REAL_CAPTURE_DATE` is set) normalizes a
  capture from the compose lake and prints counts:
  `LV_REAL_CAPTURE_DATE=2026-10-31 LV_TEST_HEAP=1600m DUCKDB_MEMORY_LIMIT=256MB make gradle ARGS=":cli:test --tests *RealCaptureCheck"`
  (about 1 minute; needs `make up`; stop postgres first if memory is tight).
- Docker/compose: the `build` service mounts the Docker socket (`group_add` from `DOCKER_GID`,
  exported by the Makefile) for Testcontainers; `make gradle ARGS=...` runs any Gradle task;
  the `cli` image ships DuckDB httpfs 1.4.5 (`DUCKDB_EXTENSION_DIRECTORY=/opt/duckdb/extensions`);
  the `cli` service has working defaults without `.env`. Run CLI steps with
  `docker compose --profile core run --rm cli <step> --date D` (the profile is needed for
  `depends_on`), and rebuild it with `docker compose --profile core --profile tools build cli`.
- Real data in the compose lake: capture 2026-10-31 (raw 239 MB, lake 44 MB). Re-running
  `download` and `lake` for it changes nothing.

## What exists (Milestone 4)

- `lake/SnapshotLoader`: lake rows → normalizers → `Snapshot`, with counts per file
  (`FileCounts`), orphan and duplicate roles. `RealCaptureCheck` uses it.
- `diff`: `SnapshotDiff.diff(A, B)` (pure; terminated companies get only `Terminated`),
  `EventJson` (canonical payloads and JSON Lines records), `EventIds` (sha256 id, sorted,
  collision check), `StoredEvent`, `LakeCaptures` (capture dates from `register/date=*`),
  `DiffStep` (previous capture = latest earlier one; baseline = empty file;
  `events/date=D/events.jsonl` via `putAtomically`).
- Tests: unit tests per event type and the termination rule, canonical JSON, ids; jqwik
  properties (`diff(A, A)` empty, replay of `diff(A, B)` on A gives B for tracked fields, ids
  stable and unique; checked by breaking the diff on purpose); `DiffStepTest` end to end
  (synthetic CSVs → LakeWriter → loader → diff). 165 tests in `make build`.
- CLI: `diff` wired; the `cli` service runs with `JAVA_OPTS=-Xmx2g -XX:+UseSerialGC`
  (`CLI_JAVA_OPTS`). Real run 2026-10-31 → 2026-11-01: 4 events, peak 2.2 GB, 1 min 53 s.
- Lake now holds captures 2026-10-31 and 2026-11-01 (raw, Parquet, events).

## Milestone 4: still open (merged early, owner's choice 2026-11-01)

The owner wants one week of data, not daily captures (2026-11-01): daily captures only if a
week turns out to be too little.
1. Capture on **Monday 2026-11-09** (after Sunday 2026-11-08 22:00 UTC, so the Riga date is
   right), then diff against 2026-11-01:
   `for s in download lake diff quality index; do docker compose --profile core run --rm cli $s --date 2026-11-09; done`
2. Pick 5 events of different types (company-level ones: name, address, status, registered,
   terminated) and give them to the owner to check on info.ur.gov.lv (a JavaScript app; or use
   a browser tool). Then tick the two PLAN.md boxes.
3. Check finding 4.4 (role ids stable?) once a capture has added roles.
Already given to the owner: the 4 events of 2026-11-01 (40008002194, 40008180344, 40008363948:
board member removed; 47404003378: farm owner removed).

## What exists (Milestone 5)

- `lake/CaptureCounts`: the diff step now loads the current snapshot on every run (also the
  baseline) and writes `reports/date=D/normalize.json` (counts per file, snapshot figures).
- `quality`: `QualityReport` (JSON, schema version 1), `ReportBuilder` (pure, warnings),
  `Thresholds` (`QUALITY_*`, `STORAGE_WARN_GB`), `RequiredColumns`, `QualityStep` (row counts
  and NULL counts through DuckDB, bucket sizes through `ObjectStore.totalSize`, FAILED report
  when an input is missing). Legacy reorganization codes have their own rule
  `reorganizations.legacy_regcode`, ignored by the drop-share warning.
- `serving`: `CompanyView` (+ Address/Money/Role/Party views), `EventView`, `RunView`,
  `SearchHit`, `Page`, `ServingWriter`, `ServingQueries`, `Views` (domain → views);
  `ServingContract` in test fixtures (13 tests).
- `serving-postgres`: `PostgresServing` (JDBC, Hikari, Flyway `V1__serving_schema.sql`),
  `PostgresSettings` (`SERVING_*`). Passes the contract against `postgres:18.6-alpine3.24`.
- `index`: `IndexStep` (refuses without a quality report or with a FAILED one; writes the run
  last).
- `cli`: `quality` and `index` wired; `SERVING_BACKEND=postgres`; one `RunContext` for steps
  and logs. `PipelineLogsTest`: the five steps for two synthetic captures against SeaweedFS,
  PostgreSQL and a fake CKAN; no person names or masked codes in any JSON log line.
- ArchUnit serving rules no longer allow an empty selection; checked by adding a violation.
- Real data: both captures have normalize counts, quality reports (OK, 0 warnings) and are
  indexed. Index 2026-10-31: 489,963 companies in 3.5 min; 2026-11-01: 4 written, 489,959
  unchanged. Peak cli 2.2 GB, postgres 236 MB; database 600 MB. Search "rigas udens" →
  `SIA "Rīgas ūdens"` first, 1.6 s cold.
- The `run` command still fails at `prune` (not implemented; retention is section 6.2, planned
  with Dagster in Milestone 7).

## What exists (Milestone 6)

- `docs/FAQ.md`: why PostgreSQL, dbt, Dagster, marts, SeaweedFS and weekly captures; linked
  from README, PLAN.md and docs/architecture.md.
- `orchestration/`: `Dockerfile` (python:3.12.16-slim-bookworm, httpfs + json baked in) and
  `requirements.txt` (duckdb 1.4.5, dbt-core 1.12.6, dbt-duckdb 1.11.1). Dagster joins this
  image in Milestone 7. Compose service `dbt` (profile tools, mounts `./transform`).
- `transform/`: dbt project (see docs/architecture.md "Transformation with dbt"): sources,
  seed `legal_forms`, 12 staging models, `int_companies`, `int_company_roles`,
  `int_snapshot_diff`, 5 marts, 5 singular tests, exposures. `profiles.yml` targets `lake`
  (SeaweedFS) and `fixtures` (local). `LV_LAKE_ROOT` sets the lake (dbt does not render
  `env_var` inside project `vars`, so it is read with `env_var` where used).
- `transform/fixtures/lake`: 3 synthetic captures (340 KB, Parquet + events) from
  `DbtFixturesTest` (diff module); regenerate with
  `LV_WRITE_DBT_FIXTURES=1 make gradle ARGS=":diff:test --tests *DbtFixturesTest"`.
- Make: `dbt-build` (real lake, ~4 min), `dbt-fixtures` (CI), `dbt-docs` (port 8081).
  Compose needs `--profile core` even with `--no-deps`, because `dbt` depends on `lake-init`.
- Serving: `StatView`, `FeatureView`, `replaceStats`, `replaceFeature`, `stats`, `feature`
  (contract: 15 tests); Postgres migration V2 (`stats`, `features`). `MartReader` in `index`
  loads `companies_by_form_status_region`, `events_per_capture`, `mass_registration_addresses`.
- `DuckDb.queryRows` for small results.
- CI runs `make dbt-fixtures` after the Gradle build.
- Real data: dbt build green on both captures (parity included); 330 mass-registration
  addresses (top 185); serving holds 3,000 stat rows and 330 feature rows.

## What exists (Milestone 7)

- `modules/pipes`: Dagster Pipes protocol 0.1 (`PipesSession`, `PipesParams`, `PipesContext`,
  `PipesMetadata`, `Severity`). Tested against parameters encoded by dagster-pipes 1.13.28.
- CLI: `Step` returns `StepOutcome` (metadata, checks); `StepRunner` reports them under Pipes
  (blocking failures as ERROR) and allows one step per process there. `Wiring` builds the
  metadata and checks per step and wires `prune`.
- `quality`: `Checks` (pure; names grouped by asset), `CheckResult`, `PruneStep` (RAW_KEEP,
  LAKE_KEEP, safety rules), `Thresholds.eventHardShare` (`QUALITY_EVENT_HARD_SHARE`, 0.10).
- `ingest`: `Downloader` fetches only today's capture (Riga date); `RawLayout.captures`.
  `diff`: `LakeCaptures.requirePreviousInLake`. `lake`: `LakeReader.columns`. `index`:
  `MartReader.companies`.
- `orchestration/lvregister_orchestration`: `partitions`, `java_assets` (6 assets through
  `PipesDockerClient`, check specs, download retry, freshness policy), `dbt_assets`
  (translator: lake sources → `lake_snapshot`, staging → quality gate), `automation` (jobs,
  `weekly_capture`, `daily_housekeeping`, `run_failure_alert`), `definitions`, `commands`
  (`capture-now`, `sync-partitions`, `status`). 13 pytest tests (`make dagster-test`, in CI).
- `docker/dagster/dagster.yaml` (Postgres storage with `scheme: postgresql+psycopg2`, one run
  at a time, compute logs on the `dagster-storage` volume, tick retention, freshness daemon) and
  `workspace.yaml`, mounted read-only. Compose profile `orchestration`: `dagster-code`,
  `dagster-webserver` (:3000), `dagster-daemon`. The orchestration image is built from the
  repository root with the dbt manifest baked in.
- dbt: `no_events_on_baseline` and the parity test stay valid after prune (checked on the
  fixtures with the oldest snapshot deleted).
- Real data: `make capture-now` for 2026-11-01 ran the whole pipeline through Dagster in about
  25 minutes (download 24 s, lake 11 s, diff 12.6 min, quality 24 s, dbt 7.3 min, index
  4.2 min, prune 13 s). All checks passed; 4 events as before; `counts_match_marts` compared
  489,963 = 489,963; index wrote nothing (idempotent). Peak memory 2.96 GB of 3.8 GB.
  `make capture-off` / `capture-on` work. Dagster idle: about 420 MB.
- Reprocessing: `make capture-now DATE=2026-10-31` (a past capture) ran green in about 23 min:
  download fetched nothing (11 skipped), lake unchanged, diff baseline (0 events), index wrote
  nothing (the serving upsert keeps the newer state). Peak 2.56 GB.
- Lesson: start Dagster only through make (`make up`, `make dagster`); without `DOCKER_GID` the
  code server cannot reach the Docker socket. Its health check now says so. Run monitoring is
  on, so a run whose worker died does not block the queue.
- Removed a `dbt.log` committed by mistake in Milestone 6 (compiled SQL only, no data).

## What exists (Milestone 8)

- `modules/api` (Spring Boot 4.1.2, the only Spring module): `ApiApplication`, `ServingConfig`
  (adapter by `SERVING_BACKEND`, never migrates), `CompanyController`, `FeedController`,
  `api.dto.Dtos`, `ApiExceptionHandler` (ProblemDetail), `RateLimitFilter` (Bucket4j, per IP,
  burst 30, 120/min), `WebConfig` (CORS for `http://localhost:5173`), `ServingHealth`,
  `CachedQueries` + `CacheConfig` (Caffeine: stats, search, 10 min). Config in
  `application.yml`; env names in `.env.example` (`API_*`).
- Tests: `ApiControllerTest` (10, `@WebMvcTest`), `RateLimitTest`, `ApiIntegrationTest`
  (`@SpringBootTest` + PostgreSQL Testcontainer: endpoints, health, OpenAPI paths),
  `ApiArchitectureTest` (adapter only in `ServingConfig`, never `ServingWriter`, no SQL or
  pipeline modules).
- Serving: `PartyLink`, `ServingQueries.companies`, `rolesOfParties`, `Network`,
  `NetworkBuilder` (two hops, owner's choice; 30 companies, 300 nodes), `ServingNames`.
  Postgres V3 `party_roles` (trigger + backfill: 671,912 links, 388,644 parties) and V4
  `company_search` (narrow search table, trigger). Broad queries (more than 1,000 substring
  matches) skip trigram ranking. Contract suite: 20 tests in the Postgres adapter.
- Dockerfile target `api`; compose service `api` (profile core, 127.0.0.1:8080, `-Xmx256m`,
  bash `/dev/tcp` health check: the JRE image has no curl); `make api`; CI builds the image.
- Real data: all endpoints answer; API about 200 MB; network of the busiest party 0.05 s
  (105 nodes, truncated); search typical 0.03–0.4 s, the commonest word ("sabiedriba",
  193,000 names) 2.8 s uncached (6 s before the narrow table and broad ranking).
- ROADMAP.md section 5: group module folders into shared / pipeline / api (owner: later).

## What exists (Milestone 9)

- `web/`: React 19 + TypeScript 5.9 + Vite 8; shadcn/ui 4.23 (Radix, "Nova" preset) in
  `src/components/ui`; TanStack Query hooks over an openapi-fetch client (`src/api`); pages
  Overview, Search, Company, Network, Changes, Pipeline (+ run detail), Insights; helpers in
  `src/lib` with Vitest tests (7). Routes lazy-loaded.
- API contract: `web/openapi.json`, kept current by `ApiIntegrationTest` (`make openapi`);
  `OpenApiConfig` marks DTO fields required and JSpecify `@Nullable` fields nullable;
  `JsonNode` as free-form object. Types generated on every build (`src/api/schema.d.ts`,
  git-ignored).
- Network page: levels 1–3 and role toggles in the URL, `filterNetwork` in the browser; d3
  layout computed up front (300 ticks) and zoomed to fit.
- Look: North Data's colours from their `site.min.css` (teal `#007388`, `#de800d`, `#a35956`,
  `#527b41`, `#5474d1`), Roboto + Roboto Mono (OFL, self-hosted); no North Data name or logo.
- Production: Dockerfile stages `web` and `api-jar` (the cli image does not depend on the
  frontend); `FrontendController` forwards client routes to `index.html`.
- Compose: `web` (tools, npm as the host user) and `web-dev` (profile dev, :5173). Make:
  `npm`, `web-check` (CI), `web-dev`, `web-smoke` (Playwright 1.64.1 in its container against
  the running stack; screenshots in `.cache/screenshots`, never committed), `openapi`.
- Real stack: every page renders with no console errors; about 1 s per page warm (up to 25 s
  right after an API restart on the busy 1-CPU VM).
- `npm audit`: 0 findings in production dependencies; 14 moderate in dev-only tooling (one
  `minimatch` advisory via openapi-typescript and typescript-eslint); the offered fix would
  downgrade typescript-eslint, so it is left.
- CLAUDE.md: document everything in Markdown and write FAQ entries after every milestone.

## Milestone 10: next steps, in order

0. After the Milestone 9 PR is merged: `git switch dev && git pull && git switch -c milestone/10-polish`.
1. Read PLAN.md Milestone 10. README: problem, architecture diagram, screenshots (frontend,
   Dagster asset graph), run in under 5 commands, design decisions. Screenshots must not show
   real person names: use synthetic data (the dbt fixtures or a demo company) or blur them;
   ask the owner.
2. Milestone 4 leftovers: the 2026-11-09 capture (Dagster's schedule takes it if the stack is
   up) and the owner's spot-check of 5 events.

## Pinned versions

| What | Version | Note |
|---|---|---|
| Java | 25 (Temurin 25.0.5_8, jdk/jre noble) | |
| Gradle | 9.8.2 | |
| DuckDB CLI / JDBC | 1.4.5 / 1.4.5.0 | 1.4 LTS. 2.0 binaries need AVX; the VM CPU (QEMU) has none |
| Picocli | 4.7.7 | |
| SLF4J / Logback | 2.0.20 / 1.5.38 | logback 1.5 line: logstash-logback-encoder 9.0 is built on it |
| logstash-logback-encoder | 9.0 | uses Jackson 3 (`tools.jackson`), as the plan requires |
| Jackson | 3.2.4 (`tools.jackson`) | manifest and CKAN JSON; records, `Optional` and `java.time` work without extra modules |
| JUnit / AssertJ / ArchUnit | 6.1.3 / 3.27.7 / 1.5.1 | ArchUnit core artifact with plain `@Test` (no `archunit-junit5` engine, to avoid JUnit 6 engine issues) |
| AWS SDK v2 | 2.57.8 | only as the S3 protocol client for SeaweedFS; `url-connection-client`, Apache and Netty clients excluded; checksums `WHEN_REQUIRED` |
| Testcontainers | 2.0.5 | core module only, containers started in `@BeforeAll` (no JUnit extension, to stay clear of JUnit 6 issues) |
| DuckDB httpfs extension | 1.4.5, linux_amd64 | baked into the cli image by checksum (`Dockerfile`, stage `duckdb-extensions`); tests download it at run time |
| FastCSV | dropped | DuckDB is the only CSV parser (owner decision 2026-10-31) |
| SeaweedFS | 4.51 | |
| Python image | python:3.12.16-slim-bookworm | orchestration image |
| dbt-core / dbt-duckdb / duckdb (Python) | 1.12.6 / 1.11.1 / 1.4.5 | pinned in `orchestration/requirements.txt` |
| Dagster / dagster-pipes / dagster-webserver | 1.13.28 | owner decision 2026-11-01; Python 3.12 |
| dagster-postgres / dagster-docker / dagster-dbt | 0.29.28 | released together with Dagster 1.13.28; `pip check` clean |
| pytest | 9.2.0 | orchestration tests |
| Spring Boot | 4.1.2 (Spring Framework 7.0.10) | owner 2026-11-01; BOM as a plain Gradle platform, so the catalog's newer Jackson 3.2.4, Flyway 13.12.0, HikariCP 7.1.0, JUnit 6.1.3 win over Boot's 3.1.7, 12.4.0, 7.0.2, 6.0.3 |
| springdoc-openapi | 3.2.0 | built against Boot 4.1.2 |
| Bucket4j | 8.21.0 (`com.bucket4j:bucket4j_jdk17-core`) | `bucket4j-core` stopped at 8.10.1 |
| Caffeine | 3.3.1 | Boot manages 3.2.4 |
| Node image | node:24.22.2-alpine | LTS; npm 11.19.1 |
| React / Vite / TypeScript | 19.3.1 / 8.3.5 / 5.9.3 | TypeScript 7.0 is out, but openapi-typescript needs 5.x and typescript-eslint < 6.1 |
| shadcn (CLI) / radix-ui / Tailwind CSS | 4.23.0 / 1.8.1 / 4.3.3 | Radix base, Nova preset; `cn` 0.5.1 is shadcn's own class merger |
| TanStack Query / React Router | 5.105.1 / 8.5.1 | |
| Recharts / d3 | 3.10.1 / 7.9.0 | the shadcn chart component installed 3.8.0; set back to 3.10.1, works |
| openapi-typescript / openapi-fetch | 7.13.0 / 0.17.0 | |
| ESLint / typescript-eslint / Vitest | 10.14.0 / 8.72.1 / 5.0.5 | |
| Playwright | 1.64.1 (image mcr.microsoft.com/playwright:v1.64.1-noble) | `make web-smoke` only |
| PostgreSQL image | 18.6-alpine3.24 | 19 is still RC (2026-10-31) |
| PostgreSQL JDBC / HikariCP / Flyway | 42.7.14 / 7.1.0 / 13.12.0 | `flyway-database-postgresql` 13.12.0 at runtime; works with PostgreSQL 18.6 |
| testcontainers-postgresql | 2.0.5 | `org.testcontainers.postgresql.PostgreSQLContainer` |
| jqwik | 1.10.2 | works on JUnit 6 (checked) |

Use `repo1.maven.org/.../maven-metadata.xml` for version lookups: the
`search.maven.org` index is stale.

## Environment facts

- Host: Alpine Linux 3.24, Docker 29.9.2, Compose v5.6, git, GNU make. `doas`, not `sudo`.
  No perl or python on the host. Host uid 1001; docker group gid 102.
- VM: 1 CPU, 4 GB RAM (after the owner's upgrade), 58 GB disk, no AVX. Gradle runs with no
  daemon, no parallel builds and `-Xmx1g`. Stop the core stack (`make down`) before heavy
  builds if memory is tight. With lake + Postgres up, about 0.8 GB is in use
  (it was about 1.5 GB with Elasticsearch).
- Dagster UI: http://localhost:3000 (`make up` or `make dagster`). Do not run `make build` while a
  capture runs: Gradle (about 1.5 GB) next to diff (2.2 GB) and Dagster does not fit.
- The Docker daemon does not always come back after a VM restart. Check it with `docker info`;
  if it's down, the owner starts it with `! doas rc-service docker start` in the Claude
  session.
- Compose variables are `HOST_UID`/`HOST_GID`, because `UID` is read-only in bash. The
  Makefile exports them.
- Create `data/` yourself before the first `docker compose run duckdb`, otherwise Docker creates
  it as root (`make duckdb` does this).
- The 2026-10-31 capture is in `data/raw/date=2026-10-31/` (git-ignored, contains person
  data). `scripts/dq.sh "<sql>"` runs SQL on it in the duckdb container (macro `csv(f)`
  reads a file as all-VARCHAR).
- SeaweedFS S3 dev keys: `lvdev` / `lvdev-local-only-secret` (local only, in
  `docker/seaweedfs/s3.json` and `.env.example`). DuckDB secret: `ENDPOINT 'lake:8333'`,
  `URL_STYLE 'path'`, `USE_SSL false`.

## Decisions log

| Date | Decision |
|---|---|
| 2026-10-31 | MVP files: register, officers, members, members_joint_owners, stockholders, stockholders_joint_owners, beneficial_owners, equity_capitals, liquidations, insolvency_legal_person_proceeding, reorganizations |
| 2026-10-31 | `CompanyStatus`: ACTIVE, IN_LIQUIDATION, INSOLVENT, LEGAL_PROTECTION, TERMINATED, REORGANIZED |
| 2026-10-31 | On termination, emit only `Terminated`, no role-removal events |
| 2026-10-31 | DuckDB pinned to the 1.4 LTS line |
| 2026-10-31 | `make` installed on the host (owner's choice; the host rule is now "Docker, git and make") |
| 2026-10-31 | Resource limits: deferred; the owner then increased the VM to 4 GB RAM (still 1 CPU) |
| 2026-10-31 | Push only after a milestone builds green (superseded below by the branch flow) |
| 2026-10-31 | `LegalForm` is a record `(code, LegalFamily)`, not one sealed record per family; `NaturalPerson`/`PersonKey` `toString()` redacted; `Money` strips trailing zeros; base package `lvregister` |
| 2026-10-31 | Compose profile `tools` for the one-shot build, cli and duckdb containers; `make up` starts only long-running profiles (owner approved; PLAN.md 6.1 updated) |
| 2026-10-31 | Serving store: PostgreSQL instead of Elasticsearch (lower RAM on the 4 GB VM). One Postgres 18 instance with databases `lv_serving` and `dagster`; plain JDBC + HikariCP; Flyway; search with `pg_trgm` + `unaccent`. Behind the `serving` port (`ServingWriter`, `ServingQueries`) with a contract test suite, so an Elasticsearch adapter can replace it (ROADMAP.md section 4). The `index` module stays as the indexing step. Owner chose all four options. |
| 2026-10-31 | Base package and Gradle group renamed from `io.github.cashewcodes9.lvregister` to `lvregister` (after the repository name, not the GitHub user; owner request) |
| 2026-10-31 | Branch flow: work from `dev`; one `milestone/<n>-<name>` branch per milestone; PR into `dev`, merged by Claude after green CI; `dev` → `main` only when the owner asks. CI on `main`/`dev` pushes and PRs; `actions/checkout@v5` (v4 ran on deprecated Node 20) |
| 2026-10-31 | Object storage is SeaweedFS only: `S3_ENDPOINT` required, no AWS fallback; the AWS SDK is just the S3 protocol client (owner) |
| 2026-10-31 | Downloader finds files through the CKAN API, not a pinned URL list (owner) |
| 2026-10-31 | FastCSV dropped: DuckDB is the only CSV parser (owner) |
| 2026-10-31 | Raw idempotency: a file already in the capture's manifest is not downloaded again; a capture is fixed once written |
| 2026-10-31 | Status precedence: closed L/R, then termination date, then INSOLVENCY, then legal protection, then liquidation, else ACTIVE |
| 2026-10-31 | Capital = registered equity, else paid-up equity |
| 2026-10-31 | Officers without position and governing body are kept and flagged (12,447 real rows, mostly IK representatives) |
| 2026-10-31 | Person key = sha256(upper-case normalized name + masked code, else birth date); no qualifier, no key |
| 2026-10-31 | Legal form families for the rare codes as in `LegalForms` (register S codes → ASSOCIATION; legacy enterprise codes → OTHER) |
| 2026-11-01 | jqwik 1.10.2 works on JUnit 6 (checked with a spike) |
| 2026-11-01 | Termination rule taken literally: a company that becomes closed gets only `Terminated` (no StatusChanged, no role events) |
| 2026-11-01 | Capture dates follow Europe/Riga: capture after 22:00 UTC (winter) for the next date |
| 2026-11-01 | Milestone 4 merged with the real-data items open (owner) |
| 2026-11-01 | Weekly data: next capture 2026-11-09, diff against 2026-11-01; daily captures only if more data is needed (owner) |
| 2026-11-01 | diff writes the normalize counts (`normalize.json`) for quality; index loads the snapshot again (1 GB is fine, owner) |
| 2026-11-01 | Serving read model: plain view records stored as documents; `asOf` = capture in which the state was first seen; companies only rewritten when their content hash changes |
| 2026-11-01 | Runs keyed by run id (several runs per capture are kept) |
| 2026-11-01 | Milestone 6 choices made with the recommended options (owner said "go on"): one orchestration image for dbt and later Dagster; dbt reads the events JSON Lines; pip with exact versions |
| 2026-11-01 | Parity test compares capture, company, event type and new value of name/status/legal form, not ids or payloads |
| 2026-11-01 | dbt fixtures are generated by the Java pipeline (`DbtFixturesTest`) and committed; CI builds dbt on them |
| 2026-11-01 | Stats and features in the serving store are replaced as a whole on every index run |
| 2026-11-01 | Milestone 7: Dagster 1.13.28 + integrations 0.29.28; own Pipes implementation in `pipes` (official Java library archived, Jackson 2); Dagster fits the VM when runs and steps never overlap (owner: "all recommended") |
| 2026-11-01 | Checks computed in Java (`Checks`), only declared in Dagster; blocking: files_complete, schema_unchanged, event_spike (hard share 0.10), drop_share, register_not_empty |
| 2026-11-01 | Dynamic partitions (captures actually made); dbt assets share them; dbt runs behind the quality gate |
| 2026-11-01 | `download` refuses past dates unless the manifest is complete; `diff` refuses when its previous capture was pruned; manifests are never pruned |
| 2026-11-01 | Dagster run retention through a daily job (OSS Dagster only has tick retention); freshness via `FreshnessPolicy` and the freshness daemon (the check + sensor API is superseded) |
| 2026-11-01 | Milestone 8: Spring Boot 4.1.2, one version per library with the catalog winning, a two-hop network, rate limit 120/min with a burst of 30 (owner) |
| 2026-11-01 | Network data: `party_roles` derived from the company documents by a trigger (the writer is unchanged); graph built in plain Java (`NetworkBuilder`) through the port |
| 2026-11-01 | Search on a narrow `company_search` table; queries with more than 1,000 substring matches are ranked exact, prefix, shorter names (no trigram similarity over every match) |
| 2026-11-01 | The API never migrates the serving schema; the index step owns it (V3/V4 were applied by running `index` once) |
| 2026-11-01 | Module folders stay flat; grouping into shared / pipeline / api is on ROADMAP.md (owner) |
| 2026-11-01 | Frontend UI: shadcn/ui (Radix + Tailwind) instead of a component library; Semantic UI dropped (unmaintained, no React 19); network graph in d3.js (owner) |
| 2026-11-01 | Charts: shadcn charts (Recharts); network in d3.js (owner, option 1a); versions as proposed (owner did not object) |
| 2026-11-01 | Frontend colours and fonts after North Data's site; no North Data name or logo (owner asked for their branding; name and logo left out to avoid looking like their product) |
| 2026-11-01 | Network page starts at level 1 (company and its people), levels 2–3 and role toggles on demand, filtered in the browser (owner) |
| 2026-11-01 | The overview's region chart was replaced by top legal forms: `region_code` is a VZD code without names in our data |
| 2026-11-01 | `web/openapi.json` is the committed API contract; a Java test keeps it current, types are generated from it |
