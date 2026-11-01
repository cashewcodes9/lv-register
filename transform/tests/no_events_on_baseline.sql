-- The first capture ever made is the baseline: it has no events. prune may delete old
-- snapshots from the lake, so the first capture is the earliest one that the lake or any
-- event (as its previous capture) knows.
with first_capture as (
    select least(
        (select min(capture_date) from {{ ref('int_companies') }}),
        coalesce((select min(previous_capture) from {{ ref('stg_events') }}), date '9999-12-31')
    ) as capture_date
)

select *
from {{ ref('fct_company_events') }}
where capture_date = (select capture_date from first_capture)
