-- Officers, members, stockholders and BOs of companies that are not in the register of the
-- same capture (orphans; 0 in the real captures). Warns: the quality report counts them too.
{{ config(severity = 'warn') }}
with roles as (
    select capture_date, company_reg_number from {{ ref('stg_officers') }}
    union all select capture_date, company_reg_number from {{ ref('stg_members') }}
    union all select capture_date, company_reg_number from {{ ref('stg_stockholders') }}
    union all select capture_date, company_reg_number from {{ ref('stg_beneficial_owners') }}
)
select r.*
from roles r
anti join {{ ref('int_companies') }} c on c.capture_date = r.capture_date and c.reg_number = r.company_reg_number
where r.company_reg_number is not null
