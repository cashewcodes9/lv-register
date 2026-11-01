-- Every role of every company and capture as a value signature, so role sets can be
-- compared as in the Java diff (Role records compared by value). Same rules as the Java
-- RoleNormalizers; roles of companies that are not in int_companies (orphans) are left out.
-- role_group: 'officer' (ManagementChanged) or 'ownership' (members, stockholders, BOs).
{% set natural %}'np|' || name || '|' || coalesce('code:' || masked_code, 'birth:' || birth_date, ''){% endset %}

with officer_holders as (
    select *,
        case
            when entity_type = 'NATURAL_PERSON' and name is not null then {{ natural }}
            when entity_type = 'LEGAL_ENTITY' and name is not null and holder_reg_number is not null
                then 'le|' || holder_reg_number || '|' || name
            when entity_type = 'FOREIGN_ENTITY' and name is not null then 'fe|' || name
        end as holder
    from {{ ref('stg_officers') }}
),

officers as (
    select capture_date, company_reg_number as reg_number, 'officer' as role_group,
           'officer|' || holder || '|' || coalesce(position, '') || '|' || coalesce(governing_body, '') || '|'
               || coalesce(cast(since as varchar), '') as signature
    from officer_holders
    where company_reg_number is not null and holder is not null
),

joint_rows as (
    select capture_date, 'MEMBERS' as share_register, * exclude (capture_date) from {{ ref('stg_members_joint_owners') }}
    union all
    select capture_date, 'STOCKHOLDERS', * exclude (capture_date) from {{ ref('stg_stockholders_joint_owners') }}
),

joint as (
    select capture_date, share_register, member_id,
           count(distinct holder) as owners,
           min(holder) as only_owner,
           'jo|' || string_agg(distinct holder, '#' order by holder) as joint_holder
    from (
        select *,
            case
                when entity_type = 'NATURAL_PERSON' and name is not null then {{ natural }}
                when entity_type = 'LEGAL_ENTITY' and name is not null and holder_reg_number is not null
                    then 'le|' || holder_reg_number || '|' || name
                when entity_type = 'FOREIGN_ENTITY' and name is not null then 'fe|' || name
            end as holder
        from joint_rows
    )
    where member_id is not null and holder is not null
    group by all
),

share_rows as (
    select * from {{ ref('stg_members') }}
    union all by name
    select * from {{ ref('stg_stockholders') }}
),

shareholders as (
    select capture_date, reg_number, 'ownership' as role_group,
           'share|' || share_register || '|' || holder || '|' || shares || '|'
               || coalesce(nominal_amount || ' ' || nominal_currency, '') || '|' || coalesce(cast(since as varchar), '')
               as signature
    from (
        select s.*, s.company_reg_number as reg_number,
            case
                when s.entity_type = 'JOINT_OWNERS' then case when j.owners = 1 then j.only_owner else j.joint_holder end
                when s.entity_type = 'DEPOSITORY' and s.depository_reg_number is not null and s.depository_name is not null
                    then 'le|' || s.depository_reg_number || '|' || s.depository_name
                when s.entity_type = 'NATURAL_PERSON' and s.name is not null
                    then 'np|' || s.name || '|' || coalesce('code:' || s.masked_code, 'birth:' || s.birth_date, '')
                when s.entity_type = 'LEGAL_ENTITY' and s.name is not null and s.holder_reg_number is not null
                    then 'le|' || s.holder_reg_number || '|' || s.name
                when s.entity_type = 'FOREIGN_ENTITY' and s.name is not null then 'fe|' || s.name
            end as holder
        from share_rows s
        left join joint j
            on j.capture_date = s.capture_date and j.share_register = s.share_register and j.member_id = s.member_id
    )
    where company_reg_number is not null and shares is not null and holder is not null
),

owners as (
    select capture_date, company_reg_number as reg_number, 'ownership' as role_group,
           'bo|' || {{ natural }} || '|' || array_to_string(nationalities, ',') || '|' || coalesce(residence, '') || '|'
               || coalesce(cast(since as varchar), '') as signature
    from {{ ref('stg_beneficial_owners') }}
    where company_reg_number is not null and name is not null
),

all_roles as (
    select * from officers
    union all select * from shareholders
    union all select * from owners
)

select distinct r.capture_date, r.reg_number, r.role_group, r.signature
from all_roles r
join {{ ref('int_companies') }} c on c.capture_date = r.capture_date and c.reg_number = r.reg_number
