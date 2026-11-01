-- Every change event of every capture, as written by the Java diff (the source of truth).
select
    event_id,
    event_type,
    reg_number,
    previous_capture,
    capture_date,
    datediff('day', previous_capture, capture_date) as gap_days,
    cast(payload as varchar) as payload
from {{ ref('stg_events') }}
