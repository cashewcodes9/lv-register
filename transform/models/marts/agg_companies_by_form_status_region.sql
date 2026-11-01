-- Companies of the latest capture per legal family, legal form, status and region (VZD code;
-- 0 = no region, e.g. Riga). For the overview page.
select
    legal_family,
    legal_form_code,
    status,
    region_code,
    count(*) as companies,
    max(as_of) as as_of
from {{ ref('dim_companies') }}
group by all
