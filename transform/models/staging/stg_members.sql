select
    capture_date,
    'MEMBERS' as share_register,
    {{ clean_text('id') }} as member_id,
    {{ reg_number('at_legal_entity_registration_number') }} as company_reg_number,
    {{ clean_code('entity_type') }} as entity_type,
    {{ clean_text('name') }} as name,
    {{ clean_text('latvian_identity_number_masked') }} as masked_code,
    {{ clean_text('birth_date') }} as birth_date,
    {{ reg_number('legal_entity_registration_number') }} as holder_reg_number,
    cast(null as varchar) as depository_reg_number,
    cast(null as varchar) as depository_name,    {{ clean_decimal('number_of_shares') }} as shares,
    {{ clean_decimal('share_nominal_value') }} as nominal_amount,
    case when regexp_full_match({{ clean_code('share_currency') }}, '[A-Z]{3}') then {{ clean_code('share_currency') }} end as nominal_currency,
    {{ clean_date('date_from') }} as since
from {{ source('lake', 'members') }}
