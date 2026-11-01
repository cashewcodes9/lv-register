-- The latest state of every company: one row per registration number, from the latest capture.
with latest as (select max(capture_date) as capture_date from {{ ref('int_companies') }}),

roles as (
    select reg_number,
           count(*) filter (where role_group = 'officer') as officers,
           count(*) filter (where role_group = 'ownership') as owners
    from {{ ref('int_company_roles') }}
    where capture_date = (select capture_date from latest)
    group by all
)

select
    c.reg_number,
    c.name,
    c.legal_form_code,
    c.legal_family,
    c.status,
    c.status in ('TERMINATED', 'REORGANIZED') as is_closed,
    c.registered,
    c.terminated,
    c.address_line,
    c.postal_code,
    c.vzd_id,
    c.atvk,
    c.region_code,
    try_cast(c.capital_amount as decimal(38, 2)) as capital_amount,
    c.capital_currency,
    coalesce(r.officers, 0) as officers,
    coalesce(r.owners, 0) as owners,
    c.capture_date as as_of
from {{ ref('int_companies') }} c
left join roles r on r.reg_number = c.reg_number
where c.capture_date = (select capture_date from latest)
