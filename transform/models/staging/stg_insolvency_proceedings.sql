-- Open and closed proceedings; open while ended_on is NULL (a malformed date reads as missing, as in Java).
select
    capture_date,
    {{ reg_number('debtor_registration_number') }} as company_reg_number,
    {{ clean_code('proceeding_form') }} as proceeding_form,
    {{ clean_date('proceeding_started_on') }} as started_on,
    {{ clean_date('proceeding_ended_on') }} as ended_on
from {{ source('lake', 'insolvency_legal_person_proceeding') }}
