# Roadmap

What comes after the MVP in [PLAN.md](PLAN.md), in order. The MVP keeps the minimum needed
to notice a missed or broken day: JSON logs with run ids, Dagster asset checks, freshness
checks and a run failure sensor, Actuator health, and the frontend's Pipeline page.

## 1. Observability and monitoring

Why: UR publishes only the current state, so a day that is not captured is lost for good.
Dagster shows which asset broke, but not trends over weeks, and it cannot alert when nothing
ran at all.

**Signals:**

| Signal | Source | Path | Store |
|---|---|---|---|
| Logs | all containers, JSON to stdout (already in the MVP) | Grafana Alloy, Docker discovery | Loki |
| Metrics, API | Micrometer via Actuator | OTLP to Alloy | Prometheus |
| Metrics, pipeline | OpenTelemetry SDK in the CLI, flushed before exit (batch jobs cannot be scraped) | OTLP to Alloy | Prometheus |
| Metrics, infrastructure | Elasticsearch and Postgres exporters, SeaweedFS metrics endpoint, container metrics | Prometheus scrape | Prometheus |
| Traces | OpenTelemetry Java agent in the API (HTTP, Elasticsearch client); spans per step in the CLI | OTLP to Alloy | Tempo |

- Correlation: the MVP's log fields (`run_id`, `dagster_run_id`, `step`, `partition_date`)
  become metric labels and span attributes too. API logs carry the trace id. Grafana links
  logs to traces and Dagster runs.
- Pipeline metrics: rows read, kept and dropped per file and rule; events per type; step
  duration; bytes downloaded; last successful partition date per step; documents indexed.

**Dashboards** (JSON in `docker/grafana/dashboards/`, provisioned):

| Dashboard | Shows |
|---|---|
| Pipeline health | last successful day per step, run duration, failures, freshness |
| Data quality | rows kept and dropped per rule, orphans, unmapped values, events per type with thresholds |
| API | request rate, error rate, p50/p95/p99 latency, rate-limit rejections, cache hits |
| Infrastructure | Elasticsearch health, heap and disk; SeaweedFS storage; JVM; Postgres; container CPU and memory |

**Alerts** (Grafana rules as code, each with an entry in `docs/runbook.md`):

| Alert | Why it matters |
|---|---|
| no successful `download` for today by the deadline | the day is lost for good |
| a step failed after retries | data stops flowing |
| drop share above 1% for a file | source format changed or a rule is wrong |
| event type above its spike threshold | usually a parsing problem |
| orphans or unmapped values grew day over day | source drift |
| API 5xx above 1% or p95 above 500 ms for 10 min | users affected |
| Elasticsearch not green, or a volume above 80% disk | serving at risk |

Thresholds start as listed and are tuned on real data.

**Work:**
- [ ] Compose profile `observability`: `alloy`, `prometheus`, `loki`, `tempo`, `grafana`,
      `es-exporter`, `pg-exporter`; configs in `docker/<service>/`
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

- [ ] AWS S3 instead of SeaweedFS (configuration only)
- [ ] Managed Elasticsearch
- [ ] Dagster on a VM or Kubernetes
- [ ] Grafana Cloud instead of the local observability stack
