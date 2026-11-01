-- One row per company and capture, with the rules of the Java RegisterNormalizer and
-- Statuses: invalid rows dropped, the first of duplicate registration numbers kept, status
-- from register.closed, the termination date and the open proceedings, capital from equity.
with valid as (
    select *
    from {{ ref('stg_register') }}
    where reg_number is not null
      and name is not null
      and regexp_full_match(legal_form_code, '[A-Z]{2,4}')
      and not (registered is not null and terminated is not null and terminated < registered)
),

first_per_reg as (
    select * from valid
    qualify row_number() over (partition by capture_date, reg_number order by file_row_number) = 1
),

liquidating as (
    select distinct capture_date, company_reg_number
    from {{ ref('stg_liquidations') }}
    where company_reg_number is not null
),

open_forms as (
    select capture_date, company_reg_number,
           bool_or(proceeding_form = 'INSOLVENCY') as insolvent,
           bool_or(proceeding_form in ('LEGAL_PROTECTION', 'OUT_OF_COURT_LEGAL_PROTECTION')) as legal_protection
    from {{ ref('stg_insolvency_proceedings') }}
    where company_reg_number is not null and proceeding_form is not null and ended_on is null
    group by all
),

capital as (
    select capture_date, company_reg_number, amount, currency
    from {{ ref('stg_equity_capitals') }}
    where company_reg_number is not null and amount is not null and currency is not null
      and capital_type in ('REGISTERED_EQUITY', 'PAID_UP_EQUITY')
    qualify row_number() over (partition by capture_date, company_reg_number
                               order by case capital_type when 'REGISTERED_EQUITY' then 0 else 1 end) = 1
)

select
    c.capture_date,
    c.reg_number,
    c.name,
    c.legal_form_code,
    coalesce(f.family, 'OTHER') as legal_family,
    f.family is null as legal_form_unmapped,
    case
        when c.closed_flag = 'L' then 'TERMINATED'
        when c.closed_flag = 'R' then 'REORGANIZED'
        when c.terminated is not null then 'TERMINATED'
        when o.insolvent then 'INSOLVENT'
        when o.legal_protection then 'LEGAL_PROTECTION'
        when l.company_reg_number is not null then 'IN_LIQUIDATION'
        else 'ACTIVE'
    end as status,
    c.registered,
    c.terminated,
    c.address_line,
    case when c.address_line is not null then c.postal_code end as postal_code,
    case when c.address_line is not null then c.vzd_id end as vzd_id,
    case when c.address_line is not null then c.atvk end as atvk,
    c.region_code,
    k.amount as capital_amount,
    k.currency as capital_currency
from first_per_reg c
left join {{ ref('legal_forms') }} f on f.code = c.legal_form_code
left join liquidating l on l.capture_date = c.capture_date and l.company_reg_number = c.reg_number
left join open_forms o on o.capture_date = c.capture_date and o.company_reg_number = c.reg_number
left join capital k on k.capture_date = c.capture_date and k.company_reg_number = c.reg_number
