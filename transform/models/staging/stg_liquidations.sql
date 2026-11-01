-- Only open liquidations are listed.
select
    capture_date,
    {{ reg_number('legal_entity_registration_number') }} as company_reg_number,
    {{ clean_code('liquidation_type') }} as liquidation_type,
    {{ clean_date('date_from') }} as since
from {{ source('lake', 'liquidations') }}
