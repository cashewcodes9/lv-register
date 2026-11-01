select capture_date, event_id, count(*) as n
from {{ ref('fct_company_events') }}
group by all
having count(*) > 1
