-- Aggregates and value features from the dbt marts (Milestone 6). Replaced as a whole on every run.
CREATE TABLE stats (
    stat       text NOT NULL,
    key        text NOT NULL,
    dimensions jsonb NOT NULL,
    value      bigint NOT NULL,
    PRIMARY KEY (stat, key)
);

CREATE TABLE features (
    feature text NOT NULL,
    key     text NOT NULL,
    rank    integer NOT NULL,
    doc     json NOT NULL,
    PRIMARY KEY (feature, key)
);
CREATE INDEX features_rank ON features (feature, rank);
