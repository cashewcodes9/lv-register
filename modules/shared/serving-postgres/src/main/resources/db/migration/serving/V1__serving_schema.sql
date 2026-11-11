-- Serving store (docs/architecture.md, "Serving"). Documents are written by the index step.

CREATE EXTENSION IF NOT EXISTS pg_trgm;
CREATE EXTENSION IF NOT EXISTS unaccent;

-- unaccent() is only STABLE; a generated column needs an IMMUTABLE function. The dictionary
-- is fixed, so wrapping it is safe.
CREATE FUNCTION lv_unaccent(text) RETURNS text
    LANGUAGE sql IMMUTABLE PARALLEL SAFE STRICT
    AS $$ SELECT unaccent('unaccent'::regdictionary, $1) $$;

CREATE TABLE companies (
    reg_number   text PRIMARY KEY,
    name         text NOT NULL,
    name_search  text GENERATED ALWAYS AS (lower(lv_unaccent(name))) STORED,
    legal_form   text NOT NULL,
    legal_family text NOT NULL,
    status       text NOT NULL,
    as_of        date NOT NULL,
    doc          jsonb NOT NULL,
    doc_hash     text NOT NULL,
    updated_at   timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX companies_name_trgm ON companies USING gin (name_search gin_trgm_ops);
CREATE INDEX companies_status ON companies (status);
CREATE INDEX companies_legal_family ON companies (legal_family);

-- Payloads keep the canonical JSON text of the diff step (json, not jsonb).
CREATE TABLE events (
    id         text PRIMARY KEY,
    type       text NOT NULL,
    reg_number text NOT NULL,
    previous   date NOT NULL,
    current    date NOT NULL,
    payload    json NOT NULL
);
CREATE INDEX events_company ON events (reg_number, current DESC);
CREATE INDEX events_feed ON events (current DESC, type);

CREATE TABLE runs (
    run_id       text PRIMARY KEY,
    capture_date date NOT NULL,
    status       text NOT NULL,
    report       json NOT NULL,
    updated_at   timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX runs_capture ON runs (capture_date DESC);
