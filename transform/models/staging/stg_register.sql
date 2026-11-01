-- register.csv, cleaned and typed. No rows removed here; validity rules are in int_companies.
select
    capture_date,
    file_row_number,
    {{ reg_number('regcode') }} as reg_number,
    {{ clean_text('name') }} as name,
    {{ clean_code('type') }} as legal_form_code,
    {{ clean_date('registered') }} as registered,
    {{ clean_date('terminated') }} as terminated,
    coalesce(trim(closed), '') as closed_flag,
    {{ clean_text('address') }} as address_line,
    case when regexp_full_match({{ clean_text('"index"') }}, '\d{4}') then {{ clean_text('"index"') }} end as postal_code,
    case when regexp_full_match({{ clean_text('addressid') }}, '\d{1,18}') and cast({{ clean_text('addressid') }} as bigint) > 0
         then cast({{ clean_text('addressid') }} as bigint) end as vzd_id,
    {{ clean_text('atvk') }} as atvk,
    {{ clean_text('region') }} as region_code
from {{ source('lake', 'register') }}
