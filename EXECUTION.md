# Execution log

Where the work stands, so a new session can continue without re-deriving context. Updated at
the end of every work session and every milestone (see CLAUDE.md). The plan itself is
[PLAN.md](PLAN.md); decisions about the source are in
[docs/source-assessment.md](docs/source-assessment.md).

**Last updated:** 2026-10-31 · **Current milestone:** 3 (not started)

## Status

| Milestone | State |
|---|---|
| 1. Source exploration | done, approved by owner 2026-10-31 |
| 2. Project setup + domain | done 2026-10-31: `make build` green from a clean clone (3m 35s, cold cache) |
| 3–10 | not started |

Git: everything is committed on `main`, but **not pushed**: GitHub rejected the push because
the token lacks the `workflow` scope needed for `.github/workflows/ci.yml`. The owner refreshes
it (`gh auth refresh -s workflow`), then run `git push origin main` and check the first CI run.

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

## Milestone 3: next steps, in order

1. Read PLAN.md Milestone 3 and the sections it refers to (storage, ingest manifest, lake layout).
2. `storage`: an `ObjectStore` interface with an S3 version (AWS SDK v2, path style, endpoint from
   `S3_ENDPOINT`) and a local-directory version. Test the S3 version with Testcontainers
   (SeaweedFS image 4.51).
3. Testcontainers from the `build` container: mount `/var/run/docker.sock`, add the socket's
   group (gid 102 on this VM; use `group_add` with a `DOCKER_GID` variable that the Makefile
   reads with `stat -c %g`), set `TESTCONTAINERS_HOST_OVERRIDE=host.docker.internal` plus
   `extra_hosts: host.docker.internal:host-gateway`. Check that it works in CI too.
4. Downloader with a sha256 manifest in `s3://lv-raw`, skip unchanged files, write through a
   temp key.
5. Lake writer (CSV to Parquet with DuckDB JDBC 1.4.5.0 `httpfs`, source sha in the metadata),
   normalizers that return `Cleaned<T>`, legal form and status mapping with tests.
6. Ask the owner before choosing a library that isn't in the pinned table below.

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
| Jackson | 3.2.x | when needed (Milestone 3+) |
| JUnit / AssertJ / ArchUnit | 6.1.3 / 3.27.7 / 1.5.1 | ArchUnit core artifact with plain `@Test` (no `archunit-junit5` engine, to avoid JUnit 6 engine issues) |
| jqwik | 1.10.x | Milestone 4; check JUnit 6 compatibility |
| AWS SDK v2 / FastCSV / Testcontainers | 2.57.x / 4.4.0 / 2.0.x | Milestone 3+; FastCSV handles quoted multi-line fields |
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
| 2026-10-31 | Push only after a milestone builds green |
| 2026-10-31 | `LegalForm` is a record `(code, LegalFamily)`, not one sealed record per family; `NaturalPerson`/`PersonKey` `toString()` redacted; `Money` strips trailing zeros; base package `io.github.cashewcodes9.lvregister` |
| 2026-10-31 | Compose profile `tools` for the one-shot build, cli and duckdb containers; `make up` starts only long-running profiles (owner approved; PLAN.md 6.1 updated) |
| 2026-10-31 | Serving store: PostgreSQL instead of Elasticsearch (lower RAM on the 4 GB VM). One Postgres 18 instance with databases `lv_serving` and `dagster`; plain JDBC + HikariCP; Flyway; search with `pg_trgm` + `unaccent`. Behind the `serving` port (`ServingWriter`, `ServingQueries`) with a contract test suite, so an Elasticsearch adapter can replace it (ROADMAP.md section 4). The `index` module stays as the indexing step. Owner chose all four options. |
