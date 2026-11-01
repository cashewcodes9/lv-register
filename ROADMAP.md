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
