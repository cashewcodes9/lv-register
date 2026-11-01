# Roadmap

What comes after the MVP in [PLAN.md](PLAN.md), in order. The MVP keeps the minimum needed
to notice a missed or broken capture: JSON logs with run ids, Dagster asset checks, freshness
checks and a run failure sensor, Actuator health, and the frontend's Pipeline page.

## 1. Observability and monitoring

Why: UR publishes only the current state, so a capture that is missed is lost for good.
Dagster shows which asset broke, but not trends over weeks, and it cannot alert when nothing
ran at all.

**Signals:**

| Signal | Source | Path | Store |
|---|---|---|---|
| Logs | all containers, JSON to stdout (already in the MVP) | Grafana Alloy, Docker discovery | Loki |
| Metrics, API | Micrometer via Actuator | OTLP to Alloy | Prometheus |
| Metrics, pipeline | OpenTelemetry SDK in the CLI, flushed before exit (batch jobs cannot be scraped) | OTLP to Alloy | Prometheus |
| Metrics, infrastructure | Postgres exporter, SeaweedFS metrics endpoint, container metrics | Prometheus scrape | Prometheus |
| Traces | OpenTelemetry Java agent in the API (HTTP, JDBC); spans per step in the CLI | OTLP to Alloy | Tempo |

- Correlation: the MVP's log fields (`run_id`, `dagster_run_id`, `step`, `partition_date`)
  become metric labels and span attributes too. API logs carry the trace id. Grafana links
  logs to traces and Dagster runs.
- Pipeline metrics: rows read, kept and dropped per file and rule; events per type; step
  duration; bytes downloaded; last successful capture date per step; storage per bucket;
  documents indexed.

**Dashboards** (JSON in `docker/grafana/dashboards/`, provisioned):

| Dashboard | Shows |
|---|---|
| Pipeline health | last successful capture per step, run duration, failures, freshness, storage |
| Data quality | rows kept and dropped per rule, orphans, unmapped values, events per type with thresholds |
| API | request rate, error rate, p50/p95/p99 latency, rate-limit rejections, cache hits |
| Infrastructure | Postgres connections, size and slow queries; SeaweedFS storage; JVM; container CPU and memory |

**Alerts** (Grafana rules as code, each with an entry in `docs/runbook.md`):

| Alert | Why it matters |
|---|---|
| no successful capture in 8 days while the schedule is on | that week is lost for good |
| a step failed after retries | data stops flowing |
| drop share above 1% for a file | source format changed or a rule is wrong |
| event type above its spike threshold | usually a parsing problem |
| orphans or unmapped values grew since the previous capture | source drift |
| API 5xx above 1% or p95 above 500 ms for 10 min | users affected |
| Postgres down or out of connections, or a volume above 80% disk | serving at risk |

Thresholds start as listed and are tuned on real data.

**Work:**
- [ ] Compose profile `observability`: `alloy`, `prometheus`, `loki`, `tempo`, `grafana`,
      `pg-exporter`; configs in `docker/<service>/`
- [ ] `telemetry` Java module (no Spring): OTel SDK setup for the CLI, metric and span names,
      flush on exit
- [ ] API: Micrometer OTLP registry, OTel Java agent in the `api` image
- [ ] Four provisioned dashboards; alert rules and contact point as code (optional webhook in
      `.env`)
- [ ] `docs/runbook.md`: what each alert means and what to do
- [ ] Trigger each alert once on purpose and record that it fired
- [ ] Frontend Pipeline page links each run to its Grafana view
- [ ] ADR: observability stack (OpenTelemetry, Grafana, Prometheus, Loki, Tempo)

## 2. More data

- [ ] Financial statements from the State Revenue Service (VID)
- [ ] Deepen Lithuania coverage the same way
- [ ] Data contracts: schema checks on the UR files that fail `download` when columns change

## 3. Cloud deployment

- [ ] A hosted S3-compatible store instead of the local SeaweedFS (configuration only: `S3_ENDPOINT`)
- [ ] Managed PostgreSQL (or managed Elasticsearch, if the adapter below is built)
- [ ] Dagster on a VM or Kubernetes
- [ ] Grafana Cloud instead of the local observability stack

## 4. Elasticsearch serving adapter

The MVP serves from PostgreSQL behind the `serving` port (PLAN.md section 4,
docs/architecture.md "Serving"). Elasticsearch becomes worth it when search needs more than
trigram matching: relevance tuning, facets, synonyms, or much more data and traffic.

- [ ] `serving-elasticsearch` module: `ServingWriter` and `ServingQueries` on the Elasticsearch
      Java client, indexes `companies`, `events`, `runs`, `stats`, `features` (document id =
      registration number, event id, run id, aggregate or feature key)
- [ ] Passes the `serving` contract test suite against an Elasticsearch Testcontainer
- [ ] Compose service `elasticsearch` (pinned 8.x or 9.x, single node, security off locally,
      heap from `.env`) in its own profile `search`
- [ ] `SERVING_BACKEND=elasticsearch` switches the CLI and the API; Actuator gets an
      Elasticsearch health indicator
- [ ] Optional: dual write during a migration, then compare query results from both adapters
- [ ] ADR: when and why serving moved (or not) to Elasticsearch

## 5. Module folders grouped by who uses them

Today every Java module sits flat under `modules/`. Gradle dependencies and the ArchUnit tests
already enforce the boundaries (the API reads only through the `serving` port and never
touches the pipeline), so this is about making the structure visible at a glance, not about
correctness. Raised by the owner on 2026-11-01; kept for later.

```
modules/
  shared/     domain, serving, serving-postgres          used by the CLI and the API
  pipeline/   storage, ingest, lake, normalize, diff,    CLI only
              quality, index, pipes, cli
  api/        api                                        Spring Boot read API
```

- [ ] Move the directories; Gradle project names (`:domain`, `:cli`, ...) stay, only the
      `projectDir` mapping in `settings.gradle.kts` changes
- [ ] Update paths in the Makefile (`dagster-test` mounts `Checks.java`), docs and README
- [ ] Optional: an ArchUnit or Gradle rule that `api` depends on `shared` modules only
- [ ] Its own small PR, with no other changes, so the moves are easy to review

## 6. Companies by region, with names

The overview was meant to show companies by region (PLAN.md section 14). The register gives
only codes, so the chart was replaced by "top legal forms" in Milestone 9:

- `region`, `city`: codes of the State Address Register (VZD, Valsts adrešu reģistrs), for
  example `100016583` for a municipality; `region` is `0` when the address is in a state city
  outside any municipality (Riga, Jūrmala, …), so "0" is mostly Riga
- `atvk`: the administrative territory code of the Central Statistical Bureau (CSP), 7 digits;
  NULL for 65,512 rows (source assessment)

Plan:

- [ ] Look up the names in VZD's open data on data.gov.lv (territorial units: municipalities
      and state cities, with the same codes the register uses). About 60 rows that change only
      with a territorial reform: a dbt seed (`seeds/territories.csv`) with code, name and type
      is enough, no weekly download. Record the source URL, date and licence in the seed's
      docs; check the licence first
- [ ] Location of a company: `city` when `region` is `0` (state cities), else `region`
- [ ] Optional: CSP's ATVK classifier (open data) to add the six statistical regions (Rīga,
      Pierīga, Vidzeme, Kurzeme, Zemgale, Latgale): a readable chart of 6 bars, plus the top 10
      municipalities
- [ ] The 2021 administrative reform (119 municipalities → 43): terminated companies may
      carry old codes. Map them to their successor where the classifier gives one, else show
      "former municipality"; a dbt test that every code of an active company has a name
- [ ] Mart `agg_companies_by_region` (region name, type, status, companies), loaded by the
      index step as a stat like the others (no port change); the overview chart comes back
      with names
- [ ] Check first: do the VZD codes in the published dataset match every `region` and `city`
      value in the register? Count the unmatched ones on a real capture

## 7. Search performance

A search took about 15 s in the frontend (2026-11-01). The measurements are in
[docs/search-performance.md](docs/search-performance.md). The causes: overlapping searches
that are never cancelled, the expensive page statement for broad queries, and a 1 CPU VM
that is short on memory. This affects users now, so it can come before sections 1–6.

Frontend:

- [ ] Pass TanStack Query's `signal` to `api.GET` in `useSearch` (and the other hooks), so
      superseded requests are aborted
- [ ] Debounce 300 → 400 ms, and decide with the owner whether search starts at 3
      characters instead of 2 (two-letter queries are the slowest: 2.5–4.5 s cold)

API and SQL (`serving-postgres`; the `serving` contract tests must stay green):

- [ ] Broad page: sort the substring matches, take the 20 rows, then compute similarity only
      for those rows (measured 2.6–4 times faster: `ri` 2,024 → 774 ms, `sia` 1,018 → 251 ms)
- [ ] Optionally cap the count at 1,001 and show "1,000+ matches" (the pager needs a total; the
      `Page` contract would carry "at least")
- [ ] `SET LOCAL statement_timeout` for search (e.g. 5 s), so an abandoned or runaway query
      can't hold a connection and the CPU; the API answers with a ProblemDetail
- [ ] Limit concurrent searches (a semaphore of 1–2 in the API, or a separate small pool)
      so searches don't slow each other down on 1 CPU

Machine:

- [ ] `shared_buffers` 128 → 256 MB (`company_search` with its index is 121 MB), and check
      swap again with Dagster idle
- [ ] Re-measure with `max_parallel_workers_per_gather = 0`; no clear win on 1 CPU so far

Measuring:

- [ ] A repeatable benchmark in a container (`make bench-search`): the three statements per
      query, median of 5, and API times sequential and overlapping; results go into
      docs/search-performance.md
- [ ] Log search duration and broad/fuzzy per request (no query text at INFO level);
      the p95 latency dashboard and alert in section 1 cover the long term
- [ ] Done when: typing `rigas udens` at normal speed on a cold cache shows results in under
      1 s on the dev VM, and no single search takes more than 2 s
