select
    capture_date,
    {{ reg_number('at_legal_entity_registration_number') }} as company_reg_number,
    {{ clean_code('entity_type') }} as entity_type,
    {{ clean_text('name') }} as name,
    {{ clean_text('latvian_identity_number_masked') }} as masked_code,
    {{ clean_text('birth_date') }} as birth_date,
    {{ reg_number('legal_entity_registration_number') }} as holder_reg_number,
    {{ clean_code('position') }} as position,
    {{ clean_code('governing_body') }} as governing_body,
    {{ clean_date('registered_on') }} as since
from {{ source('lake', 'officers') }}
