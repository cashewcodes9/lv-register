-- The snapshot diff in SQL (PLAN.md section 8), to check the Java diff (parity test). For
-- each capture and the previous capture in the lake: REGISTERED, MISSING_FROM_SNAPSHOT,
-- TERMINATED (only that, when a company becomes closed), and one event per changed field.
-- new_value: the new name, status or legal form code for those event types.
with captures as (
    select capture_date, lag(capture_date) over (order by capture_date) as previous_capture
    from (select distinct capture_date from {{ ref('int_companies') }})
),

pairs as (select * from captures where previous_capture is not null),

role_sets as (
    select capture_date, reg_number,
           string_agg(signature, chr(31) order by signature) filter (where role_group = 'officer') as officers,
           string_agg(signature, chr(31) order by signature) filter (where role_group = 'ownership') as ownership
    from {{ ref('int_company_roles') }}
    group by all
),

side as (
    select c.*, r.officers, r.ownership
    from {{ ref('int_companies') }} c
    left join role_sets r on r.capture_date = c.capture_date and r.reg_number = c.reg_number
),

changes as (
    select
        p.capture_date,
        p.previous_capture,
        b.reg_number,
        a.name <> b.name as name_changed, b.name as name_b,
        (a.address_line, a.postal_code, a.vzd_id, a.atvk) is distinct from (b.address_line, b.postal_code, b.vzd_id, b.atvk)
            as address_changed,
        a.legal_form_code <> b.legal_form_code as form_changed, b.legal_form_code as form_b,
        a.status <> b.status as status_changed, b.status as status_b,
        a.officers is distinct from b.officers as officers_changed,
        a.ownership is distinct from b.ownership as ownership_changed,
        (a.terminated is null and b.terminated is not null)
            or (a.status not in ('TERMINATED', 'REORGANIZED') and b.status in ('TERMINATED', 'REORGANIZED')) as became_closed
    from pairs p
    join side a on a.capture_date = p.previous_capture
    join side b on b.capture_date = p.capture_date and b.reg_number = a.reg_number
)

select p.capture_date, p.previous_capture, b.reg_number, 'REGISTERED' as event_type, cast(null as varchar) as new_value
from pairs p
join side b on b.capture_date = p.capture_date
anti join side a on a.capture_date = p.previous_capture and a.reg_number = b.reg_number
union all
select p.capture_date, p.previous_capture, a.reg_number, 'MISSING_FROM_SNAPSHOT', null
from pairs p
join side a on a.capture_date = p.previous_capture
anti join side b on b.capture_date = p.capture_date and b.reg_number = a.reg_number
union all
select capture_date, previous_capture, reg_number, 'TERMINATED', null from changes where became_closed
union all
select capture_date, previous_capture, reg_number, 'NAME_CHANGED', name_b from changes where not became_closed and name_changed
union all
select capture_date, previous_capture, reg_number, 'ADDRESS_CHANGED', null from changes where not became_closed and address_changed
union all
select capture_date, previous_capture, reg_number, 'LEGAL_FORM_CHANGED', form_b from changes where not became_closed and form_changed
union all
select capture_date, previous_capture, reg_number, 'STATUS_CHANGED', status_b from changes where not became_closed and status_changed
union all
select capture_date, previous_capture, reg_number, 'MANAGEMENT_CHANGED', null from changes where not became_closed and officers_changed
union all
select capture_date, previous_capture, reg_number, 'OWNERSHIP_CHANGED', null from changes where not became_closed and ownership_changed
