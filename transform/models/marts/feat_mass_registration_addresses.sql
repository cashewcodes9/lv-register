-- Value feature 1 (PLAN.md section 10): addresses where an unusual number of active companies
-- are registered, a known shell-company signal. Keyed by the State Address Register id (VZD);
-- addresses without a VZD id are left out (source assessment 4.7).
with active as (
    select * from {{ ref('dim_companies') }}
    where not is_closed and vzd_id is not null
),

per_address as (
    select
        vzd_id,
        mode(address_line) as address_line,
        mode(postal_code) as postal_code,
        count(*) as active_companies,
        count(*) filter (where legal_family = 'LLC') as llcs,
        count(*) filter (where registered >= as_of - interval 365 day) as registered_last_year,
        list(reg_number order by registered desc nulls last, reg_number)[1:10] as latest_reg_numbers,
        max(as_of) as as_of
    from active
    group by vzd_id
)

select
    rank() over (order by active_companies desc, vzd_id) as rank,
    *
from per_address
where active_companies >= {{ var('mass_address_min_companies') }}
