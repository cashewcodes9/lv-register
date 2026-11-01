-- The first capture in the lake is the baseline: it has no events.
select *
from {{ ref('fct_company_events') }}
where capture_date = (select min(capture_date) from {{ ref('int_companies') }})
