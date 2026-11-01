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

## 2. More data: the sources North Data combines, for Latvia

North Data's [coverage page](https://www.northdata.com/_coverage) shows the pattern per
country (for Estonia: company profiles, updates, yearly financials, beneficial-owner updates)
plus international sources on top: LEI codes and their relationships (GLEIF), sanctions lists
(EU, UK, OFAC), EU trademarks and designs (EUIPO), patents (EPO), the EU Transparency Register,
EU funding (Financial Transparency System, State Aid, Horizon), Wikidata organisations and
politically exposed persons, stock exchange data. Latvia is not on the page.

What Latvia publishes beyond the 11 register files we use, checked on data.gov.lv on
2026-11-01 (the Register of Enterprises alone has 30 datasets). Everything below is keyed by
the registration number unless noted, and CC0 unless noted.

### 2.1 Financials, taxes and employees (highest value)

- [ ] **Annual report financial data** (UR, `gada-parskatu-finansu-dati`): four CSVs,
      about 520 MB: `financial_statements` (year, period, employees, currency, rounding),
      `balance_sheets` (cash, receivables, inventories, fixed and intangible assets, total
      assets, liabilities, equity), `income_statements` (net turnover, labour and material
      expenses, depreciation, income before and after tax, net income), `cash_flow_statements`.
      Gives revenue, profit, total assets, equity and employees per company and year: North
      Data's core financial indicators. Company page: a Financials tab with a few years and
      sparklines; overview: companies by size class; events `FINANCIALS_FILED`
- [ ] **Taxes paid and employees** (VID): taxes per company for the last three years
      (46 MB: total, personal income tax, social contributions, **average employees**,
      **main NACE code**, **ATVK code and name** of the legal address) and per quarter (15 MB,
      with VAT paid and refunded). Cross-checks the employee counts of the annual reports
- [ ] **VAT payers** (VID `pvn-maksataji`, 29 MB): registered, active, excluded, dates; events
      `VAT_REGISTERED`, `VAT_EXCLUDED`
- [ ] **Taxpayer rating** (VID `nodoklu-maksataju-reitings`, 14 MB): rating with an
      explanation; event `TAX_RATING_CHANGED`

### 2.2 Industry, region and history

- [ ] **Industry**: main NACE code from the VID tax file above, named with the CSP NACE 2 /
      2.1 classifiers (`nace2`, `nace21`). UR's `area_of_activity` is free text (old
      companies), shown as is
- [ ] **Region names** (section 6): VZD address register territorial units (`varis-atvertie-
      dati`, **CC BY 4.0**: attribution required) and the CSP ATVK classifier with NUTS 3
      regions and predecessors/successors (`atvk`, CC0). The VID tax file also carries the
      ATVK name per company
- [ ] **Name history** (UR `register_name_history.csv`, 6.7 MB: registration number, valid
      until, name): names from before our first capture; search finds former names

### 2.3 Risk flags

- [ ] **Suspensions and prohibitions of activity** (UR, 1.9 MB) and **securing measures**
      (UR, 7.2 MB: type, date, case, institution): events and badges on the company page
- [ ] **Sanction risks** (UR `sanctions.csv`, 22 KB): companies linked to sanctioned
      subjects, with list, programme and legal basis. Contains person names: company-level
      flags only in the API (PLAN.md section 12)
- [ ] **Companies of national importance** (UR `special_statuses`, 10 KB)

### 2.4 Public money

- [ ] **EU funds projects** (CFLA: 2014–2020, 2021–2027 and the Recovery Fund; CC0): project,
      programme, applicant registration number, partners, places, amounts; North Data's
      "public funding"
- [ ] **State aid contracts** (LIAA, small XLSX) and **agricultural payments** (LAD; also pays
      natural persons: companies only)
- [ ] **Public procurement**: the Latvian procurement office publishes only statistics as open
      data; awards above the EU thresholds come from EU TED (international, below)

### 2.5 International sources, as North Data uses them

- [ ] **LEI** (GLEIF golden copy, CC0, daily): LEI codes of Latvian companies by registration
      number, and **parent / ultimate parent relationships** across borders
- [ ] **Sanctions screening**: EU Financial Sanctions File, UK and OFAC lists against company
      names (persons only with care: name matching produces false positives)
- [ ] **EU Transparency Register** and UR's Latvian **interest representatives** list
- [ ] **EU TED** procurement awards to Latvian companies
- [ ] **Intellectual property**: Latvian Patent Office trademarks, designs and patents (XML,
      CC0, 120 MB each for trademarks and patents; holders by name, matching needed) and EUIPO
- [ ] Later: EU Financial Transparency System, State Aid Transparency, Horizon (CORDIS),
      Wikidata organisations

### 2.6 How each source fits in

- One `SourceFile` set per publisher with its own CKAN lookup, manifest and cadence (annual
  reports and tax files change daily as filings arrive, classifiers rarely); one Dagster asset
  per source with its own checks; dbt staging models; joins by registration number
- The diff produces the new event types; the serving port gets the new views (financials,
  flags) with contract tests; the frontend a tab or badge per source
- Each source gets a section in docs/source-assessment.md first (real columns and value sets,
  as in Milestone 1), and a licence note (VZD data is CC BY 4.0, the rest checked CC0)
- Person data in any new source follows PLAN.md section 12

Order: 2.1 (financials and taxes), then industry and regions (2.2, which also fixes section 6),
then risk flags, then public money, then the international sources.

### 2.7 Also

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
- [ ] Shortcut: VID's tax file (section 2.1) already has the ATVK code and name of each
      company's legal address; it may cover most companies without a code mapping
- [ ] Check first: do the VZD codes in the published dataset match every `region` and `city`
      value in the register? Count the unmatched ones on a real capture
