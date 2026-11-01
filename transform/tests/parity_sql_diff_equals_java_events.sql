-- PLAN.md section 9: the SQL diff equals the Java events for every capture the diff step ran
-- for, as long as both snapshots of the pair are still in the lake (prune deletes old ones).
-- Compared: capture, company, event type, and the new value of NAME_CHANGED, STATUS_CHANGED
-- and LEGAL_FORM_CHANGED. Returns the rows found on only one side.
with lake as (
    select distinct capture_date from {{ ref('int_companies') }}
),

diffed as (
    select distinct cast(regexp_extract(file, 'date=(\d{4}-\d{2}-\d{2})', 1) as date) as capture_date
    from glob('{{ env_var('LV_LAKE_ROOT', 's3://lv-lake') }}/events/*/events.jsonl')
),

java as (
    select capture_date, reg_number, event_type,
           case event_type
               when 'NAME_CHANGED' then json_extract_string(payload, '$.new')
               when 'STATUS_CHANGED' then json_extract_string(payload, '$.new')
               when 'LEGAL_FORM_CHANGED' then json_extract_string(payload, '$.new.code')
           end as new_value
    from {{ ref('stg_events') }}
    where previous_capture in (select capture_date from lake)
),

sql as (
    select capture_date, reg_number, event_type, new_value
    from {{ ref('int_snapshot_diff') }}
    where capture_date in (select capture_date from diffed)
)

(select 'only in Java' as side, * from java except all select 'only in Java', * from sql)
union all
(select 'only in SQL', * from sql except all select 'only in SQL', * from java)
