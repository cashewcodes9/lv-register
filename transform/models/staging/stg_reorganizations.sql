select
    capture_date,
    {{ clean_code('reorganization_type') }} as reorganization_type,
    {{ reg_number('source_entity_regcode') }} as source_reg_number,
    {{ reg_number('final_entity_regcode') }} as target_reg_number,
    {{ clean_date('registered') }} as registered
from {{ source('lake', 'reorganizations') }}
