# lv-register

A pipeline that turns the daily open data of the Latvian Register of Enterprises
(Uzņēmumu reģistrs) into clean company snapshots and a feed of change events, with a
read-only API and a React app to explore and check the data.

Latvia is the Baltic country North Data does not cover yet. The register publishes its data
on [data.gov.lv](https://data.gov.lv/dati/lv/organization/ur) under CC0, updated daily.

**Status:** planning. Nothing runs yet.

- [PLAN.md](PLAN.md): goal, scope, tech stack and milestones
- [docs/architecture.md](docs/architecture.md): layers, pipeline, event rules, modules
- Reference design: [Oversee](https://github.com/cashewcodes9/Oversee), the same layer
  design in Python

Stack:
- **Pipeline:** Java 25, Gradle, Picocli; DuckDB over a Parquet lake on S3-compatible
  storage (SeaweedFS)
- **Models and data tests:** dbt (dbt-duckdb)
- **Orchestration:** Dagster: daily partitions, schedule, asset checks, backfills
- **Serving:** Elasticsearch, Spring Boot API (API only), React + TypeScript
- **Observability:** OpenTelemetry, Grafana, Prometheus, Loki, Tempo: dashboards and alerts
  as code

Everything builds and runs in Docker; the host needs only Docker and git.

## Personal data

Officers and beneficial owners in the register are personal data. Raw files and the lake are
never committed. The API shows persons only in the context of one company and offers no bulk
list or export. EU rules on public access to beneficial ownership may narrow what Latvia
publishes.
