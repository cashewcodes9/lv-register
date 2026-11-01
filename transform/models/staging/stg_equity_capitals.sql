select
    capture_date,
    {{ reg_number('legal_entity_registration_number') }} as company_reg_number,
    {{ clean_code('equity_capital_type') }} as capital_type,
    {{ clean_decimal('amount') }} as amount,
    case when regexp_full_match({{ clean_code('currency') }}, '[A-Z]{3}') then {{ clean_code('currency') }} end as currency
from {{ source('lake', 'equity_capitals') }}
