# Search performance: measurements and bottlenecks

On 2026-11-01 the owner saw a search take about 15 s in the frontend. This page records what
was measured, where the time goes, and what to change. The fixes are planned in
[ROADMAP.md](../ROADMAP.md#7-search-performance); nothing in the code has changed yet.

How search works is described in [architecture.md](architecture.md) ("Serving"). In short, one
search runs three SQL statements on `company_search`:

1. **probe**: are there more than 1,000 substring matches? If so, the query is "broad".
2. **count**: the total for the pager.
3. **page**: 20 rows. Broad queries are ordered by exact, prefix, then shorter names. Other
   queries ("fuzzy") are ordered by trigram similarity.

## Environment

The dev VM, measured on 2026-11-01:

| | |
|---|---|
| CPU | 1 vCPU (Proxmox VM) |
| Memory | 3.9 GB, about 1.9 GB available, **805 MB in swap** |
| PostgreSQL | 18, `shared_buffers` 128 MB, `max_parallel_workers_per_gather` 2 |
| `company_search` | 121 MB including the GIN trigram index; it barely fits in shared buffers |
| API | Spring Boot 4, Hikari pool of 4 (`API_DB_POOL_SIZE`), Caffeine cache 10 min |
| Also running | Dagster webserver, daemon and code server, SeaweedFS, the Vite dev server |

## 1. Database: SQL time per statement

The statements that `PostgresServing.search()` issues were timed with `EXPLAIN ANALYZE`, as
the median of 5 runs, in ms. Four variants were compared:

- **cur**: as coded today
- **nopar**: parallel query off (`max_parallel_workers_per_gather = 0`)
- **lazy**: like nopar, but a broad page sorts the matches first and computes similarity only
  for the 20 rows it returns
- **cap**: like lazy, but the count stops at 1,001 ("1,000+ matches")

| Query | Kind | cur | nopar | lazy | cap |
|---|---|---:|---:|---:|---:|
| `ri` | broad | 2,024 | 1,995 | 774 | **665** |
| `sia` | broad | 1,018 | 1,431 | **251** | 363 |
| `rigas` | broad | 148 | 303 | **36** | 98 |
| `kalns` | fuzzy | 606 | 277 | 278 | 430 |
| `volvo` | fuzzy | 24 | 24 | 85 | 39 |
| `brivibas` | fuzzy | 561 | 562 | 1,634 | 549 |
| `rgas` (typo) | fuzzy | 1,768 | 1,714 | 948 | 2,215 |
| `40008338813` | reg. number | 0 | 0 | 0 | 1 |

What the numbers show:

- **Broad queries spend their time in the page statement.** For `ri` the probe takes 1 ms,
  the count 118 ms and the page 1,905 ms. The page computes similarity for every substring
  match before it sorts them, even though the ordering doesn't use similarity. Computing it
  only for the 20 rows returned (lazy) makes broad queries 2.6–4 times faster.
- **Capping the count** helps only when the count itself is large. Broad counts are already
  fast (80–220 ms), so cap and lazy end up close.
- **Fuzzy queries show noise, not a difference between variants.** For fuzzy queries,
  nopar, lazy and cap run the same SQL, yet `brivibas` took 562 ms in one run and 1,634 ms in
  another. Run-to-run variance on this VM is up to 3x.
- **Parallel query** gives no clear win either way on 1 CPU.
- Earlier the same day, one query took 36 s and then 1.5 s a minute later. That size of swing
  comes from the machine (swap, other containers), not from the SQL.

## 2. API: one search at a time

Each query was sent twice to `GET /api/search?q=…&page=0&size=20` from inside the VM (curl,
total time):

| Query | First call (s) | Second call (s) |
|---|---:|---:|
| `ri` | 4.47 | 0.008 |
| `ma` | 2.51 | – |
| `zv` | 0.92 | – |
| `rig` | 0.23 | 0.011 |
| `riga` | 0.28 | 0.003 |
| `rigas` | 0.14 | 0.005 |
| `rigas u` | 0.71 | 0.004 |
| `rigas ud` | 0.89 | 0.005 |
| `rigas udens` | 0.40 | 0.006 |
| `sia` | 0.64 | 0.005 |
| `kalns` | 0.29 | 0.003 |
| `brivibas` | 0.56 | 0.005 |
| `rgas` (typo) | 1.45 | 0.004 |
| `volvo` | 0.03 | 0.005 |
| `40003000001` | 0.07 | 0.006 |
| `nams` | 0.04 | 0.005 |

- On its own, a search takes 0.03–1.5 s. **Two-letter queries are the slow ones**: `ri`
  4.5 s, `ma` 2.5 s. They are broad and touch the most rows.
- A repeated query is served from the API cache in under 12 ms.
- Some first calls may already have been cached from earlier use the same day. In that case
  the real cold times are higher.

## 3. Frontend: several searches at once

How the Search page behaves (`web/src/pages/Search.tsx`, `web/src/api/hooks.ts`):

- It waits for **300 ms** without typing (`useDebounced`), then searches, from 2 characters
  on.
- `useSearch` doesn't pass TanStack Query's abort `signal` to `fetch`, so **superseded
  searches are never cancelled**. Even if the browser did cancel them, PostgreSQL would
  finish the query anyway.
- Every pause of more than 300 ms while typing starts a new search. On a cold cache, each
  one costs up to seconds of the only CPU.

Two overlap tests, with requests not cancelled, as the frontend does today:

**Typing with short pauses.** `kr` → `krasta iela`, 8 requests 350 ms apart:

| Query | Time (s) |
|---|---:|
| `kras` | 0.48 |
| `kra` | 0.98 |
| `krast` | 0.40 |
| `kr` | 1.49 |
| `krasta` | 3.94 |
| `krasta iela` | 3.14 |
| `krasta i` | 4.25 |
| `krasta ie` | 4.39 |

The search the user actually wants (`krasta iela`, about 0.4 s alone) took 3.1 s because it
queued behind the earlier keystrokes.

**Four broad two-letter searches at once** (`ka`, `pa`, `ta`, `la`): 2.6, 2.9, 3.2 and
**6.2 s**. On their own they take about 1–2.5 s each. The pool of 4 connections lets all four
run together on 1 CPU, so each one slows down the others.

## Where the 15 s comes from

No single query takes 15 s alone. It is the sum of three things:

1. **Overlapping uncancelled searches**: each pause while typing adds a search to the queue
   (section 3). This makes the final search 3–10 times slower than it is alone.
2. **The expensive broad page**: two- and three-letter prefixes are broad and cost 1–4.5 s
   each, mostly in computing similarity for every match (section 1).
3. **A tight VM**: 1 CPU, swap in use, `company_search` barely fits in 128 MB of buffers, and
   Dagster and the dev server share the machine. A cold or swapped moment multiplies
   everything (the 36 s vs 1.5 s run).

## How it was measured

- SQL: `EXPLAIN (ANALYZE, COSTS OFF)` of the three statements per variant, 5 runs, median,
  through `docker compose exec postgres psql`. The script was a throwaway in the session's
  scratchpad. A repeatable version is a roadmap item.
- API: `curl -w %{time_total}` against `localhost:8080`, sequentially and in parallel
  (background jobs, 350 ms apart).
- Frontend: read from the code (debounce, abort signal). No browser profile was taken; the
  15 s comes from the owner's screenshot.
