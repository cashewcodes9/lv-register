# Execution log

Where the work stands, so a new session can continue without re-deriving context. Updated at
the end of every work session and every milestone (see CLAUDE.md). The plan itself is
[PLAN.md](PLAN.md); decisions about the source are in
[docs/source-assessment.md](docs/source-assessment.md).

**Last updated:** 2026-11-01 · **Current milestone:** 5 (not started); 4 has two open real-data items

## Status

| Milestone | State |
|---|---|
| 1. Source exploration | done, approved by owner 2026-10-31 |
| 2. Project setup + domain | done 2026-10-31: `make build` green from a clean clone (3m 35s, cold cache) |
| 3. Storage + ingest + lake + normalize | done 2026-10-31, merged into `dev` (PR #1, CI green) |
| 4. Diff + events | code merged into `dev` 2026-11-01 (PR #2, CI green); open: a capture a few days later, spot-check 5 events |
| 5–10 | not started |

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

1. Capture again a few days after 2026-11-01 (after 22:00 UTC, so the Riga date is right):
   `docker compose --profile core run --rm cli download --date D`, then `lake` and `diff`.
   A weekday export should give many more events.
2. Pick 5 events of different types (company-level ones: name, address, status, registered,
   terminated) and spot-check them on info.ur.gov.lv. The portal is a JavaScript app: the
   owner checks in a browser, or Claude uses a browser tool. Tick the two PLAN.md boxes.
3. Check finding 4.4 (role ids stable?) once a capture has added roles.

## Milestone 5: next steps, in order

0. `git switch dev && git pull && git switch -c milestone/5-quality-index`.
1. Read PLAN.md section 11 and docs/architecture.md "Serving".
2. `quality`: run record and quality report from `SnapshotLoader.Loaded` counts, the diff
   result, the manifest and bucket sizes; `s3://lv-lake/reports/date=D/report.json`.
   Avoid loading the snapshots twice (diff and quality in one run): decide with the owner
   whether diff writes the normalize counts, or quality re-reads them.
3. `serving` port + contract tests, `serving-postgres` with Flyway, indexer through the port.
4. Test: no person data in CLI logs on a fixture run.

Milestone 5 (serving), for later: the design is in docs/architecture.md "Serving". Write the
contract suite in `serving` testFixtures first, then the Postgres adapter against it with a
PostgreSQL Testcontainer (same image tag as compose). `unaccent()` is only STABLE: wrap it in an
IMMUTABLE SQL function for the generated search column.

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
| jqwik | 1.10.x | Milestone 4; check JUnit 6 compatibility |
| AWS SDK v2 | 2.57.8 | only as the S3 protocol client for SeaweedFS; `url-connection-client`, Apache and Netty clients excluded; checksums `WHEN_REQUIRED` |
| Testcontainers | 2.0.5 | core module only, containers started in `@BeforeAll` (no JUnit extension, to stay clear of JUnit 6 issues) |
| DuckDB httpfs extension | 1.4.5, linux_amd64 | baked into the cli image by checksum (`Dockerfile`, stage `duckdb-extensions`); tests download it at run time |
| FastCSV | dropped | DuckDB is the only CSV parser (owner decision 2026-10-31) |
| SeaweedFS | 4.51 | |
| PostgreSQL image | 18.6-alpine3.24 | 19 is still RC (2026-10-31) |
| PostgreSQL JDBC / HikariCP / Flyway | 42.7.14 / 7.1.0 / 13.12.0 | `flyway-database-postgresql` 13.12.0 at runtime |

Use `repo1.maven.org/.../maven-metadata.xml` for version lookups: the
`search.maven.org` index is stale.

## Environment facts

- Host: Alpine Linux 3.24, Docker 29.9.2, Compose v5.6, git, GNU make. `doas`, not `sudo`.
  No perl or python on the host. Host uid 1001; docker group gid 102.
- VM: 1 CPU, 4 GB RAM (after the owner's upgrade), 58 GB disk, no AVX. Gradle runs with no
  daemon, no parallel builds and `-Xmx1g`. Stop the core stack (`make down`) before heavy
  builds if memory is tight. With lake + Postgres up, about 0.8 GB is in use
  (it was about 1.5 GB with Elasticsearch).
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
