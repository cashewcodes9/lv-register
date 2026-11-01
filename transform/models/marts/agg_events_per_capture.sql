-- Events per capture and type, with the gap to the previous capture. For the overview page.
select
    capture_date,
    previous_capture,
    gap_days,
    event_type,
    count(*) as events
from {{ ref('fct_company_events') }}
group by all
