# lv-register

A pipeline that turns the open data of the Latvian Register of Enterprises
(Uzņēmumu reģistrs) into clean company snapshots and a feed of change events, with a
read-only API and a React app to explore and check the data.

Latvia is the Baltic country North Data does not cover yet. The register publishes its data
on [data.gov.lv](https://data.gov.lv/dati/lv/organization/ur) under CC0, updated daily.

**Status:** Milestones 1–9 done: the Java pipeline (download → lake → diff → quality → index →
prune) runs on real weekly captures, orchestrated by Dagster with dbt in between; a Spring Boot
API and a React frontend serve the result. Milestone 10 (polish) is next.

## Run it

```sh
cp .env.example .env      # optional: every value has a local default
make up                   # lake, Postgres, API + frontend, Dagster; waits until healthy
make capture-now          # queue today's capture in Dagster (about 25 minutes on 1 CPU)
```

| What | Where |
|---|---|
| Frontend and API | http://localhost:8080 (API under `/api`, Swagger UI at `/api/docs`) |
| Dagster | http://localhost:3000 (asset graph, runs, checks, schedule) |
| Frontend dev server with hot reload | `make web-dev`, then http://localhost:5173 |
| Everything else | `make help` |

Checks: `make build` (Java), `make dagster-test`, `make dbt-fixtures`, `make web-check`
(frontend), `make web-smoke` (headless browser against the running stack).

- [PLAN.md](PLAN.md): goal, scope, tech stack and milestones
- [docs/architecture.md](docs/architecture.md): layers, pipeline, event rules, modules
- [docs/FAQ.md](docs/FAQ.md): why the project is built this way (serving store, dbt, Dagster,
  marts, API, search, frontend, storage, capture dates)
- [docs/source-assessment.md](docs/source-assessment.md): what the UR files really contain
- [ROADMAP.md](ROADMAP.md): after the MVP, starting with observability and monitoring
- Reference design: [Oversee](https://github.com/cashewcodes9/Oversee), the same layer
  design in Python

Stack:
- **Pipeline:** Java 25, Gradle, Picocli; DuckDB over a Parquet lake on S3-compatible
  storage (SeaweedFS)
- **Models and data tests:** dbt (dbt-duckdb)
- **Orchestration:** Dagster: weekly capture schedule with an off/on switch, asset checks,
  reprocessing from the lake
- **Serving:** PostgreSQL behind a swappable serving port (Elasticsearch adapter on the
  roadmap), Spring Boot 4 (API only)
- **Frontend:** React + TypeScript (Vite), shadcn/ui, TanStack Query, Recharts, d3.js for the
  company network; colours after North Data's site

Everything builds and runs in Docker; the host needs only Docker, git and make.

## Personal data

Officers and beneficial owners in the register are personal data. Raw files and the lake are
never committed. The API shows persons only in the context of one company and offers no bulk
list or export. EU rules on public access to beneficial ownership may narrow what Latvia
publishes.
