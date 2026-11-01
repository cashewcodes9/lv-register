-- Every company in dim_companies is in the latest register capture of the lake.
with latest as (
    select reg_number from {{ ref('stg_register') }}
    where capture_date = (select max(capture_date) from {{ ref('stg_register') }})
)
select d.reg_number
from {{ ref('dim_companies') }} d
anti join latest l on l.reg_number = d.reg_number
