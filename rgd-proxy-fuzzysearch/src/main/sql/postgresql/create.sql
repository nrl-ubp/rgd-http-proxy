-- =============================================================================
-- PostgreSQL DDL – token-fuzzy-search
--
-- The PERSON table, with the same shape as the one the rgd-http-proxy
-- PersonFlightSqlLoader creates: every column but ID holds a value protected by
-- the transformer of the proxy (FPE or RPS), so every one of them is text, the
-- birth date included.
--
-- Usage:
--   psql -d token_fuzzy_search -U token_fuzzy_search -f create.sql
-- =============================================================================

CREATE TABLE IF NOT EXISTS PERSON (
    ID          INT           NOT NULL,   -- assigned by the application (MAX(ID) + 1)
    FIRST_NAME  VARCHAR(255),             -- protected, mapped like the proxy's FIRST_NAME column
    LAST_NAME   VARCHAR(255),
    BIRTH_DATE  VARCHAR(64),              -- an FPE date stays a date, an RPS one is a token
    EMAIL       VARCHAR(255),
    CITY        VARCHAR(255),
    NOTES       VARCHAR(1024),
    CONSTRAINT pk_person PRIMARY KEY (ID)
);

-- Exact-match search compares the protected names by equality
CREATE INDEX IF NOT EXISTS idx_person_first_name ON PERSON (FIRST_NAME);
CREATE INDEX IF NOT EXISTS idx_person_last_name  ON PERSON (LAST_NAME);
