-- The Java events (source of truth). capture_date = the current capture of the event.
select
    "current" as capture_date,
    id as event_id,
    type as event_type,
    regNumber as reg_number,
    previous as previous_capture,
    payload
from {{ read_events() }}
