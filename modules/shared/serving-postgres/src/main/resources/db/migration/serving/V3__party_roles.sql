-- Which companies a person or entity holds roles in, for the company network (API
-- /companies/{reg}/network). Derived from companies.doc by a trigger, so the writer is unchanged.
-- party_key: 'person:<person key>' or 'company:<registration number>'. Joint owners count
-- with each of their owners. Persons without a key and foreign entities are not linked.

CREATE TABLE party_roles (
    party_key  text NOT NULL,
    reg_number text NOT NULL REFERENCES companies (reg_number) ON DELETE CASCADE,
    role_kind  text NOT NULL,
    PRIMARY KEY (party_key, reg_number, role_kind)
);
CREATE INDEX party_roles_company ON party_roles (reg_number);

CREATE FUNCTION lv_party_roles(doc jsonb) RETURNS TABLE (party_key text, role_kind text)
    LANGUAGE sql IMMUTABLE PARALLEL SAFE
    AS $$
        SELECT DISTINCT
               CASE WHEN p->>'personKey' IS NOT NULL THEN 'person:' || (p->>'personKey')
                    ELSE 'company:' || (p->>'regNumber') END,
               r->>'kind'
        FROM jsonb_array_elements(doc->'roles') r,
             jsonb_array_elements(jsonb_build_array(r->'holder') || coalesce(r->'holder'->'owners', '[]'::jsonb)) p
        WHERE p->>'personKey' IS NOT NULL OR p->>'regNumber' IS NOT NULL
    $$;

CREATE FUNCTION lv_refresh_party_roles() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
    BEGIN
        DELETE FROM party_roles WHERE reg_number = NEW.reg_number;
        INSERT INTO party_roles (party_key, reg_number, role_kind)
            SELECT x.party_key, NEW.reg_number, x.role_kind FROM lv_party_roles(NEW.doc) x;
        RETURN NULL;
    END
    $$;

CREATE TRIGGER companies_party_roles AFTER INSERT OR UPDATE OF doc ON companies
    FOR EACH ROW EXECUTE FUNCTION lv_refresh_party_roles();

INSERT INTO party_roles (party_key, reg_number, role_kind)
    SELECT x.party_key, c.reg_number, x.role_kind FROM companies c, lv_party_roles(c.doc) x;
