# Source assessment: Latvian Register of Enterprises (UR) open data

Milestone 1. Based on one capture of every UR file on data.gov.lv, taken **2026-10-31**
(files last modified 2026-10-30 ~21:25 UTC). All figures below come from that capture,
profiled with DuckDB 1.4.5 in the `duckdb` container (`scripts/fetch-capture.sh`,
`scripts/dq.sh`). Only aggregates and code values are recorded here, never person data.

## 1. What UR publishes

The organisation `ur` on data.gov.lv has 30 datasets (CKAN API
`https://data.gov.lv/dati/api/3/action/package_search?fq=organization:ur`). All are CC0,
frequency DAILY. Resource URLs are stable
(`https://data.gov.lv/dati/dataset/<package-id>/resource/<resource-id>/download/<file>.csv`).

| File | Rows | Entities | CSV MB | Content | Relevant |
|---|---:|---:|---:|---|---|
| `register.csv` | 489,965 | 489,965 | 123.0 | every entity ever registered: name, form, address, dates, closed flag | **core** |
| `officers.csv` | 283,085 | 208,046 | 38.5 | board members, chairs, liquidators, owners of IK, ... | **core** |
| `members.csv` | 186,216 | 140,211 | 23.6 | SIA members (shareholders) with shares | **core** |
| `members_joint_owners.csv` | 516 | – | 0.03 | co-owners of a jointly held SIA share | yes |
| `stockholders.csv` | 22,491 | 763 | 3.3 | AS stockholders (only AS that keep the register at UR) | **core** |
| `stockholders_joint_owners.csv` | 40 | – | <0.01 | co-owners of jointly held stock | yes |
| `beneficial_owners.csv` | 200,346 | 164,494 | 16.9 | beneficial owners (BO) | **core** |
| `equity_capitals.csv` | 144,252 | 141,199 | 14.7 | paid-up / registered capital | yes |
| `liquidations.csv` | 9,649 | 9,276 | 1.5 | open liquidation processes | yes (status) |
| `insolvency_legal_person_proceeding.csv` | 18,053 | 15,947 | 5.9 | insolvency and legal-protection proceedings, open and closed | yes (status) |
| `reorganizations.csv` | 10,914 | – | 0.9 | mergers, transformations, divisions (source → final regcode) | yes (history) |
| `area_of_activity.csv` | 63,074 | 62,042 | 25.5 | free-text objects of activity | no (see 4.6) |
| `register_name_history.csv` | 90,974 | 73,507 | 6.4 | previous names with `date_to`; **not updated since 2025-04-02** | later |
| `financial_statements.csv`, `balance_sheets.csv`, `income_statements.csv`, `cash_flow_statements.csv` | – | – | 494 | annual-report figures | roadmap |
| others: `securing_measures`, `suspensions_prohibitions`, `memorandum_date`, `legal_entity_duration`, `special_statuses`, `sanctions`, `akf_data`, `farm_land`, `religious_affiliations`, `political_parties`, `arbitration_*`, `ppi_*`, `interest_representatives`, `property_investment_appraisers_list`, `areas_of_activity_of_associations_foundations`, RAIM statistics | small | – | ~25 | niche registers and statistics | no |

**Contradiction with PLAN.md:** the plan says financial statements are filed with VID and
not available. UR itself publishes annual-report data (dataset `gada-parskatu-finansu-dati`,
~500 MB of CSV per capture). It stays out of the MVP, but it belongs on the roadmap as an
in-reach source, not "a different authority".

## 2. Format (all files)

- **Delimiter** `;`, **header** row, **encoding UTF-8** without BOM, line ending `\n`.
- **Quoting:** most files quote text fields with `"`. `area_of_activity.csv` quotes every
  field. `liquidations.csv` and `area_of_activity.csv` contain **quoted fields with embedded
  newlines and CRLF** (9,765 lines vs 9,649 rows; 283,841 lines vs 63,074 rows). A
  line-based reader breaks; the CSV parser must support multi-line quoted fields.
- **Empty values** are empty fields (read as NULL), with one exception: `register.closed`
  is a **single space** for active entities, not empty.
- **Dates** are ISO `YYYY-MM-DD`; `last_modified_at` is `YYYY-MM-DD HH:MM:SS`.
- **Decimals** use `.` (e.g. `number_of_shares`, `share_nominal_value`, `amount`).
- **Registration numbers** are 11 digits (455,725) or **9 digits with possible leading zero**
  (34,240 old-style codes; 26,959 start with `0`). DuckDB type
  inference reads them as BIGINT in some files and drops the leading zero, so the lake reads
  **every column as VARCHAR** (`all_varchar=true`). Types are applied in `normalize`.
- **Hive auto-detection:** DuckDB adds a `date` column when reading from
  `.../date=YYYY-MM-DD/`. The lake writer sets `hive_partitioning=false` on raw reads.
- Column names are English `snake_case` (except `ppi_*` in camelCase). Code values are
  English upper-snake (`BOARD_MEMBER`), or Latvian abbreviations in `register.csv`
  (`SIA`, `K`), each with a Latvian `*_text` label.

## 3. Columns and value sets (MVP files)

### 3.1 `register.csv`, key `regcode` (unique, 489,965)

| Column | Type | Notes |
|---|---|---|
| `regcode` | varchar(9 or 11) | key; all digits |
| `sepa` | varchar | SEPA creditor id; NULL for 109,734 rows (mostly closed and media) |
| `name` | varchar | full name incl. legal form, e.g. `SIA "X"`; 2 NULL |
| `name_before_quotes`, `name_in_quotes`, `name_after_quotes` | varchar | parts of the name; `without_quotes` (0/1) says whether the name has quotes |
| `regtype`, `regtype_text` | code | which register (below) |
| `type`, `type_text` | code | legal form (below); `type_text` NULL for 6 codes |
| `registered` | date | 1990-04-17 … 2026-10-30; 94 NULL |
| `terminated` | date | NULL while active |
| `closed` | code | `L` (liquidated / removed, 259,004), `R` (reorganised into another entity, 8,385), `' '` (active, 222,576) |
| `address` | varchar | one line, Latvian format; NULL for 585 (439 active) |
| `index` | integer | postal code `LV-NNNN` without prefix; NULL 20,188 |
| `addressid` | integer | State Address Register (VZD) code; `0` = unknown (3,628 active) |
| `region`, `city` | integer | VZD codes of region and city; `0` when not applicable (Riga) |
| `atvk` | varchar | administrative territory code; NULL 65,512 |
| `reregistration_term` | date | only 22 rows |

`regtype` values: `K` Komercreģistrs (commercial register) 317,639 · `U` Uzņēmumu reģistra
žurnāls (legacy enterprise register) 127,426 · `B` associations and foundations 32,636 ·
`M` mass media 4,287 · `S` public organisations 3,641 · `C` representations 2,527 · `R`
religious 1,375 · `T` arbitration courts 228 · `P` political parties 111 · `O` political
organisations 71 · `A` trade unions 19 · `E` EEIG 5.

`type` (legal form), 61 codes, total / active:

| Code | Latvian | Total | Active | Proposed family |
|---|---|---:|---:|---|
| SIA | Sabiedrība ar ierobežotu atbildību | 339,247 | 140,212 | LLC |
| BDR | Biedrība | 30,278 | 25,992 | Association |
| ZEM | Zemnieku saimniecība | 38,961 | 23,293 | Sole proprietorship (farm) |
| IK | Individuālais komersants | 28,269 | 12,141 | Sole proprietorship |
| IND | Individuālais uzņēmums | 23,442 | 9,733 | Sole proprietorship (legacy) |
| MIL | Masu informācijas līdzeklis | 4,287 | 4,081 | Other (mass media, not a company) |
| NOD | Nodibinājums | 1,983 | 1,573 | Foundation |
| KB | Kooperatīvā sabiedrība | 3,770 | 1,424 | Cooperative |
| DRZ | Draudze | 1,031 | 943 | Religious organisation |
| AS | Akciju sabiedrība | 2,448 | 767 | Corporation |
| PS | Pilnsabiedrība | 1,128 | 537 | General partnership |
| AKF | Ārvalsts komersanta filiāle | 1,411 | 483 | Branch of a foreign company |
| KS | Komandītsabiedrība | 450 | 166 | Limited partnership |
| FIL | Filiāle | 2,056 | 116 | Branch |
| PAR | (Ārvalsts komersanta) pārstāvniecība | 2,499 | 136 | Representation |
| VU, PSV | State / municipal enterprise | 2,266 | – | Public enterprise (legacy) |
| SE | Eiropas komercsabiedrība | 10 | – | Corporation (SE) |
| … 44 more codes, each < 1,000 rows | | | | Other |

Of the 222,576 active entities, about 200K are business forms (SIA, IK, ZEM, IND, AS, KB,
PS, KS, AKF); the rest are associations, foundations, media, religious and political bodies.

Quirks: 646 rows have `terminated` set (1991–2015) but `closed` blank; 4 names and 1 address
have surrounding spaces, 7 names and 25 addresses double spaces.

### 3.2 Role files

**Only active entities.** Every officer, member, stockholder and BO row points to an entity
with `closed` blank (0 rows on closed entities, 0 orphans). When an entity is terminated, all
its roles disappear from the files in the same capture. Role files hold only the current
state: no end dates.

`officers.csv` (`id` unique, 283,085): `id`, `uri` (always NULL),
`at_legal_entity_registration_number` (company), `entity_type`, `position`,
`governing_body`, `name`, `latvian_identity_number_masked`, `birth_date`,
`legal_entity_registration_number` (when the officer is a company),
`rights_of_representation_type`, `representation_with_at_least`, `registered_on`,
`last_modified_at`.

- `entity_type`: `NATURAL_PERSON` 282,017 · `LEGAL_ENTITY` 1,007 · `FOREIGN_ENTITY` 61
- `position`: `BOARD_MEMBER` 132,244 · `CHAIR_OF_BOARD` 31,900 · `OWNER` 24,399 (IK/ZEM
  owners) · `LIQUIDATOR` 8,788 · `MEMBER` · `REPRESENTATIVE_MANAGER` · `ADMINISTRATOR` ·
  `CO_OWNER` · `AUTHORISED_REPRESENTATIVE` · `PRESIDENT` · `MANAGER` · `ESTATE_TRUSTEE` ·
  `MANAGING_DIRECTOR` · `CHAIR_OF_SUPERVISORY_BOARD` · `REPRESENTATIVE_OF_THE_LIQUIDATOR`;
  NULL for 82,252 rows, which have only a `governing_body`
- `governing_body`: `EXECUTIVE_BOARD` · `EXECUTIVE_BODY` · `MANAGEMENT_BODY` ·
  `LIQUIDATION_COMMITTEE` · `EXECUTIVE_COMMITTEE` · `DIRECTORATE` · `SUPERVISORY_BOARD` ·
  `STAFF_COMMITTEE` · `SENATE` · `AUDITOR` · NULL
- `rights_of_representation_type`: `INDIVIDUALLY` 250,228 · `WITH_AT_LEAST` (+ count 1–11+)
  · `WITH_ALL` · `WITH_CHAIR` · NULL 391

`members.csv` (186,216 rows, 140,211 SIA): `id`, `uri` (NULL),
`at_legal_entity_registration_number`, `entity_type`, `name`,
`latvian_identity_number_masked`, `birth_date`, `legal_entity_registration_number`,
`number_of_shares`, `share_nominal_value`, `share_currency` (`EUR` 174,331, `LVL` 11,885),
`date_from`, `registered_on`, `last_modified_at`.
- `entity_type`: `NATURAL_PERSON` 169,327 · `LEGAL_ENTITY` 11,188 · `FOREIGN_ENTITY` 5,530 ·
  `JOINT_OWNERS` 171 (details in `members_joint_owners.csv` via `member_id`)
- **`id` is not unique**: 12 ids appear twice, each time for two different companies. The
  row key is `(at_legal_entity_registration_number, id)`.

`stockholders.csv` (22,491 rows, 763 AS): as `members` plus `votes`, `stock_type`
(`REGISTERED_STOCKS` 18,596 · `DEMATERIALISED_STOCKS` 240 · NULL 3,655),
`depository_registration_number`, `depository_name`; `entity_type` adds `DEPOSITORY` (37).
Most AS (listed or with dematerialised stock) are not covered: the register of their
stockholders is kept by the depository.

`beneficial_owners.csv` (200,346 rows, 164,494 entities): `id`, `legal_entity_registration_number`,
`forename`, `surname`, `latvian_identity_number_masked`, `birth_date` (**year-month only**,
`YYYY-MM`, for persons without a Latvian code), `nationality`, `residence` (ISO-3166 alpha-2;
LV 90 %, then LT, RU, EE, UA, DE), `registered_on`, `last_modified_at`.

### 3.3 Status files

`liquidations.csv`: `id`, `legal_entity_registration_number`, `liquidation_type`
(`LIQUIDATION` 8,727 · `ACTIVITY_TERMINATED` 773 · `ACTIVITY_TERMINATED_BY_COURT` 119 ·
`NON_REREGISTRATION` 15 · `REFORM_OF_POLITICAL_PARTIES` 14 · `REFORM_OF_ASSOCIATIONS` 1),
`liquidation_type_text`, `date_from`, `grounds_for_liquidation` (free text, multi-line),
`registered_on`, `last_modified_at`. Only open processes of active entities; 9,649 rows for 9,276 entities, so some
entities have more than one row.

`insolvency_legal_person_proceeding.csv`: `proceeding_id`, `debtor_registration_number`,
`debtor_name`, `debtor_address`, `proceeding_started_on`, `proceeding_ended_on`,
`proceeding_form` (`INSOLVENCY` · `LEGAL_PROTECTION` · `OUT_OF_COURT_LEGAL_PROTECTION`),
`proceeding_type` (`NATIONAL` · `CROSS_BORDER`), court and creditor-deadline fields. Includes
closed proceedings (history back to the 2000s); 815 are open, all on active entities.

`reorganizations.csv`: `id`, `reorganization_type` (`ACQUISITION` 6,309 · `TRANSFORMATION`
1,856 · `SEPARATION` 1,744 · `REORGANISATION` 514 · `DIVISION` 267 · `MERGER` 224),
`reorganization_type_text`, `source_entity_regcode`, `final_entity_regcode`, `registered`.

### 3.4 `equity_capitals.csv`

`id`, `legal_entity_registration_number`, `equity_capital_type` (`PAID_UP_EQUITY` 141,018 ·
`REGISTERED_EQUITY` 3,023 · `INVESTMENT` 165 · `CONDITIONAL_EQUITY` 26 · `EQUITY_KB` 19 ·
`MINIMUM_RESERVES` 1), `equity_capital_type_text`, `amount`, `currency` (EUR, LVL, NULL),
`date_from`, `last_modified_at`. At most one row per entity and type.

## 4. Findings that affect the plan

### 4.1 Status comes from several files
`register.csv` only knows active / `L` / `R`. A North-Data-style status needs
`liquidations.csv` (in liquidation) and `insolvency_legal_person_proceeding.csv` (open
insolvency or legal protection). Proposed `CompanyStatus`: `ACTIVE`, `IN_LIQUIDATION`,
`INSOLVENT`, `LEGAL_PROTECTION`, `TERMINATED` (`L`), `REORGANIZED` (`R`, with the successor
from `reorganizations.csv` when known).

### 4.2 Roles vanish on termination (decided: emit only `Terminated`)
Because role files cover active entities only, a terminated company loses all officers,
members and BOs in the same capture. The diff must not report this as
`ManagementChanged`/`OwnershipChanged` with everyone removed. Proposal: when an entity
terminates, emit only `Terminated` and suppress role-removal events for it.

### 4.3 No stable person id
There is no person id. Natural persons with a Latvian code have
`latvian_identity_number_masked` = first 6 digits + `-*****` (e.g. `DDMMYY-*****` for old
codes; new codes since 2017 start with `32` and carry no birth date). Foreign persons have
`birth_date` instead (officers, members: `YYYY-MM-DD`; BO: `YYYY-MM`).
Proposed person key, conservative: `normalized name + masked code` when present, else
`normalized name + birth date`, else no key (the person is linked to that one company
only). Two people with the same name and the same 6 digits are possible but rare; false
merges are still possible and must be documented. Legal-entity officers and members use the
regcode as key.

### 4.4 Role identity across captures
`officers.id` is unique per capture; `members.id` is unique only per company. Whether these
ids stay stable across captures, and whether a changed board appears as a new id, can only
be checked with a second capture (Milestone 4). Until then the diff compares role sets by
value (person key + position/governing body, or person key + shares), not by id.

### 4.5 History in the source
- Role files have no end dates; the lake is the only history, as planned.
- `register_name_history.csv` gives old names with `date_to`, but stopped updating on
  2025-04-02. It could seed name history before the first capture. Not in the MVP.
- `reorganizations.csv` links source and final entities: useful to explain `R` and for
  "succeeded by" links.

### 4.6 No segment codes
UR does not publish NACE codes. `area_of_activity.csv` is free Latvian text (half the rows
longer than 200 characters, multi-line) for 62K entities only. NACE codes exist at the
State Revenue Service and Central Statistical Bureau, not in this source. The "segment codes"
feature of North Data is not covered by UR open data.

### 4.7 Mass-registration addresses are visible
`addressid` (VZD code) is a clean key for the value feature: the top address has 185
active entities, the next ones 157, 132, 124, 119. `addressid = 0` (3,628 active) and NULL
addresses must be excluded.

### 4.8 Publication time
Every file has `Last-Modified` of the previous evening around 21:25 UTC (00:25 Riga winter
time); the newest `last_modified_at` in the data was 19:24 UTC. One capture is not enough to
confirm weekend publishing. `weekly_capture` on Monday 06:00 Europe/Riga is safely after the
Sunday-night export and stays as planned.

### 4.9 Environment
The dev VM's CPU (QEMU, no AVX) cannot run DuckDB 2.0.0 binaries (illegal instruction).
DuckDB is pinned to **1.4.5 (LTS line)** in the CLI image; the DuckDB JDBC driver and
dbt-duckdb must use the same 1.4.x line. The VM also has 1 CPU and 3 GB RAM, which is tight
for Elasticsearch + Dagster + Gradle at the same time.

## 5. Sizes and retention

Per capture, MVP files (the 11 files proposed in section 7, read as all-VARCHAR,
Parquet ZSTD):

| File | CSV MB | Parquet MB |
|---|---:|---:|
| register | 123.0 | 23.9 |
| officers | 38.5 | 5.7 |
| area_of_activity (not MVP) | 25.5 | 5.2 |
| members | 23.6 | 4.5 |
| beneficial_owners | 16.9 | 4.1 |
| equity_capitals | 14.7 | 1.9 |
| insolvency | 5.9 | 0.9 |
| stockholders | 3.3 | 0.4 |
| liquidations | 1.5 | 0.3 |
| reorganizations | 0.9 | 0.1 |
| joint owners (2 files) | 0.04 | 0.02 |
| **MVP total** (without area_of_activity) | **~228** | **~42** |

All UR files together are 783 MB per capture (the financial files make up 494 MB).

With the planned retention (raw: latest 2, lake: latest 12) the MVP needs about
2 × 228 MB + 12 × 42 MB ≈ **1 GB**, plus events and reports (small). The default
`STORAGE_WARN_GB=10` leaves plenty of room; 3 GB would also be a reasonable warning level.

## 6. Mapping to North Data features

| North Data feature | UR source | Fields |
|---|---|---|
| Base data | `register`, `equity_capitals` | name, legal form, address (+ VZD id), registered, terminated, status, capital |
| History / change events | diff of weekly captures; `reorganizations`; `register_name_history` (stale) | all tracked fields |
| Status / insolvency notices | `register.closed`, `liquidations`, `insolvency_*` | status, dates, proceeding form |
| Representatives | `officers` | position, governing body, representation rights, registered_on |
| Shareholders | `members`, `stockholders`, `*_joint_owners` | holder, shares, nominal value, currency, date_from |
| Beneficial owners | `beneficial_owners` | name, nationality, residence, registered_on |
| Person networks | officers + members + stockholders + BO via the person key (4.3) and company keys | – |
| Segment codes | none (4.6) | – |
| Financials | `gada-parskatu-finansu-dati` | roadmap |

## 7. MVP files (decided 2026-10-31)

`register`, `officers`, `members`, `members_joint_owners`, `stockholders`,
`stockholders_joint_owners`, `beneficial_owners`, `equity_capitals`, `liquidations`,
`insolvency_legal_person_proceeding`, `reorganizations`.

Decided with the owner after this assessment, together with the termination rule in 4.2
(emit only `Terminated`). Not in the MVP: `area_of_activity` (free text), `register_name_history` (stale), financial
statements (roadmap), the niche registers.

## 8. Personal data

Officers, members, stockholders and BOs are personal data (names, partial personal codes,
birth dates, nationality, residence). Latvia publishes BOs openly; after the CJEU ruling of
22 November 2022 (C‑37/20, C‑601/20) several EU countries closed public BO access, and EU
AML rules (AMLD6) move BO access to a legitimate-interest model, so this may narrow. The
project keeps persons out of logs, metadata and commits, as in PLAN.md section 12.
