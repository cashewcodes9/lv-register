-- Several nationalities are listed as LV;US inside one field (source assessment 9).
select
    capture_date,
    {{ reg_number('legal_entity_registration_number') }} as company_reg_number,
    {{ clean_text("coalesce(" ~ clean_text('forename') ~ ", '') || ' ' || coalesce(" ~ clean_text('surname') ~ ", '')") }} as name,
    {{ clean_text('latvian_identity_number_masked') }} as masked_code,
    {{ clean_text('birth_date') }} as birth_date,
    list_sort(list_distinct(list_filter(
        list_transform(string_split(coalesce({{ clean_code('nationality') }}, ''), ';'), x -> trim(x)),
        x -> regexp_full_match(x, '[A-Z]{2}')))) as nationalities,
    case when regexp_full_match({{ clean_code('residence') }}, '[A-Z]{2}') then {{ clean_code('residence') }} end as residence,
    {{ clean_date('registered_on') }} as since
from {{ source('lake', 'beneficial_owners') }}
