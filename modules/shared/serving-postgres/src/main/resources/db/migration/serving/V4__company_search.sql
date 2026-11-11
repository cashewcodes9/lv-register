-- Search reads a narrow copy of the companies table. The companies heap holds every JSON
-- document (about 500 MB for the register), so a common word made the trigram search read
-- thousands of random heap blocks from disk (several seconds cold). company_search keeps only
-- what search returns (about 60 MB plus its index), which stays in memory. Kept by a trigger.

CREATE TABLE company_search (
    reg_number  text PRIMARY KEY REFERENCES companies (reg_number) ON DELETE CASCADE,
    name        text NOT NULL,
    name_search text NOT NULL,
    legal_form  text NOT NULL,
    status      text NOT NULL
);

INSERT INTO company_search (reg_number, name, name_search, legal_form, status)
    SELECT reg_number, name, name_search, legal_form, status FROM companies;

CREATE INDEX company_search_name_trgm ON company_search USING gin (name_search gin_trgm_ops);
DROP INDEX companies_name_trgm;

CREATE FUNCTION lv_refresh_company_search() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
    BEGIN
        INSERT INTO company_search (reg_number, name, name_search, legal_form, status)
            VALUES (NEW.reg_number, NEW.name, NEW.name_search, NEW.legal_form, NEW.status)
            ON CONFLICT (reg_number) DO UPDATE SET name = EXCLUDED.name, name_search = EXCLUDED.name_search,
                legal_form = EXCLUDED.legal_form, status = EXCLUDED.status;
        RETURN NULL;
    END
    $$;

CREATE TRIGGER companies_search AFTER INSERT OR UPDATE ON companies
    FOR EACH ROW EXECUTE FUNCTION lv_refresh_company_search();

ANALYZE company_search;
