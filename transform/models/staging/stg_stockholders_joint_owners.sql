select
    capture_date,
    {{ clean_text('member_id') }} as member_id,
    {{ clean_code('entity_type') }} as entity_type,
    {{ clean_text('name') }} as name,
    {{ clean_text('latvian_identity_number_masked') }} as masked_code,
    {{ clean_text('birth_date') }} as birth_date,
    {{ reg_number('legal_entity_registration_number') }} as holder_reg_number
from {{ source('lake', 'stockholders_joint_owners') }}
