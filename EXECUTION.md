# Execution log

Where the work stands, so a new session can continue without re-deriving context. Updated at
the end of every work session and every milestone (see CLAUDE.md). The plan itself is
[PLAN.md](PLAN.md); decisions about the source are in
[docs/source-assessment.md](docs/source-assessment.md).

**Last updated:** 2026-10-31 · **Current milestone:** 2 (in progress)

## Status

| Milestone | State |
|---|---|
| 1. Source exploration | done, approved by owner 2026-10-31 |
| 2. Project setup + domain | in progress, code written but **not built, not committed** |
| 3–10 | not started |

Git: 3 Milestone 1 commits on `main` are **not pushed**. Push together with Milestone 2 once
`make build` is green (owner's rule: push only after a milestone builds).

## Milestone 2: what exists on disk (uncommitted)

- `settings.gradle.kts`: modules `domain storage ingest lake normalize diff quality index pipes
  cli` under `modules/`. `api` joins in Milestone 8.
- `gradle/libs.versions.toml`: version catalog. `buildSrc/` holds the convention plugin
  `lv.java-conventions` (Java 25 toolchain, `-Xlint:all -Werror`, JUnit 6, AssertJ).
- `gradle.properties`: no daemon, `-Xmx768m`, no parallel builds. These were set for the old
  1 CPU / 3 GB VM; relax them now that the VM has more resources.
- Gradle wrapper 9.8.2 (`gradlew`, `gradle/wrapper/`), generated in `gradle:9.8.2-jdk25`.
- `modules/domain`: `RegNumber`, `LegalForm` + `LegalFamily`, `CompanyStatus`, `Address`,
  `Money`, `Company`, `PersonKey`, `Party` (sealed: NaturalPerson, LatvianEntity,
  ForeignEntity, JointOwners), `Role` (sealed: Officer, Shareholder, BeneficialOwner),
  `Snapshot`, `ObservedBetween`, `CompanyEvent` (sealed, 9 types), `Cleaned`, `Checks`.
  Tests: `ValueRecordsTest`, `CompanyEventTest`, `Fixtures` (synthetic data only).
- `modules/cli`: `Main` (Picocli; subcommands download, lake, diff, quality, index, prune,
  run; `--date`), `Step`, `StepName`, `Steps` (wiring, `notImplemented()` placeholder),
  `StepRunner` (MDC + start/finish/failure logs, exit code), `RunContext` (`run_id` from
  `LV_RUN_ID` or a UUID, `dagster_run_id` from `DAGSTER_RUN_ID`, `step`, `partition_date`),
  `src/main/resources/logback.xml` (LogstashEncoder JSON to stdout).
- Other pipeline modules have only a `build.gradle.kts` (depends on `domain`, `slf4j-api`).

### Domain choices made in Milestone 2 (tell the owner, easy to change)

- `LegalForm` is a record `(code, LegalFamily)`, not one sealed record per family as in the
  PLAN.md sketch: 61 UR codes map to a few families, and the record keeps the raw code.
- `NaturalPerson.toString()` and `PersonKey.toString()` are redacted so that person data
  cannot leak into logs through `toString`.
- `Money` and `Shareholder.shares` strip trailing zeros, so `100.00` equals `100`.
- Base package `io.github.cashewcodes9.lvregister`.

## Milestone 2: next steps, in order

1. Write the remaining tests: `cli` tests (subcommands parse `--date`, MDC keys in JSON
   output, exit code 1 for `notImplemented`) and an ArchUnit test in `modules/cli/src/test`:
   no class outside `..lvregister.api..` depends on `org.springframework..`; `domain`
   depends only on `java..`.
2. `docker/build/Dockerfile`: `eclipse-temurin:25.0.5_8-jdk-noble`, user with the host uid
   (`ARG HOST_UID`; remove the image's `ubuntu` user first), `/home/builder/.gradle` for the
   named-volume Gradle cache.
3. Root `Dockerfile`: stage `jdk` (`./gradlew :cli:installDist`), stage `cli`
   (`eclipse-temurin:25.0.5_8-jre-noble`, non-root). Stages `web` and `api` come in
   Milestones 8 and 9.
4. Extend `docker-compose.yml`: `lake` (`chrislusf/seaweedfs:4.51`, S3 :8333, filer :8888,
   `docker/seaweedfs/s3.json` with dev keys, a one-shot init that creates the `lv-raw`,
   `lv-lake` and `lv-marts` buckets via `weed shell`), `elasticsearch`
   (`elasticsearch:8.19.23`, single node, security off, heap from `.env`), `build`, `cli`,
   and the existing `duckdb`. Healthchecks, `condition: service_healthy`, profiles `core`
   and `dev`, `restart: unless-stopped`, ports bound to `127.0.0.1`, `env_file` with
   `required: false`.
5. `.env.example`: S3 endpoint, keys, region and buckets, Elasticsearch URL and heap,
   retention (`RAW_KEEP=2`, `LAKE_KEEP=12`, `STORAGE_WARN_GB=10`).
6. `Makefile`: `up`, `down`, `build`, `test`, `run DATE=`, `status`, `duckdb`,
   `fetch-capture`. The host now has GNU make 4.4.1.
7. `.github/workflows/ci.yml`: `make build` and `docker build --target cli .`.
8. Run `make build` from a clean state, fix the failures, tick the Milestone 2 boxes in
   PLAN.md, commit in small commits, push, update this file.

## Pinned versions

| What | Version | Note |
|---|---|---|
| Java | 25 (Temurin 25.0.5_8) | |
| Gradle | 9.8.2 | |
| DuckDB CLI / JDBC | 1.4.5 / 1.4.5.0 | 1.4 LTS. 2.0 binaries need AVX; the VM CPU (QEMU) has none |
| Picocli | 4.7.7 | |
| SLF4J / Logback | 2.0.20 / 1.5.38 | logback 1.5 line: logstash-logback-encoder 9.0 is built on it |
| logstash-logback-encoder | 9.0 | uses Jackson 3 (`tools.jackson`), as the plan requires |
| Jackson | 3.2.x | when needed (Milestone 3+) |
| JUnit / AssertJ / ArchUnit | 6.1.3 / 3.27.7 / 1.5.1 | |
| jqwik | 1.10.x | Milestone 4; check JUnit 6 compatibility |
| AWS SDK v2 / FastCSV / Testcontainers | 2.57.x / 4.4.0 / 2.0.x | Milestone 3+; FastCSV handles quoted multi-line fields |
| SeaweedFS / Elasticsearch | 4.51 / 8.19.23 | |

Use `repo1.maven.org/.../maven-metadata.xml` for version lookups: the
`search.maven.org` index is stale.

## Environment facts

- Host: Alpine Linux 3.24, Docker 29.9, Compose v5.6, git, GNU make (installed by the owner
  on 2026-10-31). `doas`, not `sudo`. Host uid 1001.
- VM CPU has no AVX (see DuckDB). The owner increased memory and CPUs after Milestone 1;
  re-check with `nproc` and `free -g`.
- Compose variables are `HOST_UID`/`HOST_GID`, because `UID` is read-only in bash.
- Create `data/` yourself before the first `docker compose run`, otherwise Docker creates
  it as root.
- The 2026-10-31 capture is in `data/raw/date=2026-10-31/` (git-ignored, contains person
  data). `scripts/dq.sh "<sql>"` runs SQL on it in the duckdb container (macro `csv(f)`
  reads a file as all-VARCHAR).

## Decisions log

| Date | Decision |
|---|---|
| 2026-10-31 | MVP files: register, officers, members, members_joint_owners, stockholders, stockholders_joint_owners, beneficial_owners, equity_capitals, liquidations, insolvency_legal_person_proceeding, reorganizations |
| 2026-10-31 | `CompanyStatus`: ACTIVE, IN_LIQUIDATION, INSOLVENT, LEGAL_PROTECTION, TERMINATED, REORGANIZED |
| 2026-10-31 | On termination, emit only `Terminated`, no role-removal events |
| 2026-10-31 | DuckDB pinned to the 1.4 LTS line |
| 2026-10-31 | `make` installed on the host (owner's choice; the host rule is now "Docker, git and make") |
| 2026-10-31 | Resource limits: deferred; the owner then increased VM resources |
| 2026-10-31 | Push only after a milestone builds green |
