-- =============================================================================
-- SQL Server DDL – token-fuzzy-search
--
-- The PERSON table is normally created by the rgd-http-proxy
-- PersonFlightSqlLoader (--create-table). This script creates the same table
-- when it does not exist yet, plus the indexes the exact-match search relies on.
-- =============================================================================

IF OBJECT_ID('dbo.PERSON', 'U') IS NULL
CREATE TABLE dbo.PERSON (
    ID          INT           NOT NULL PRIMARY KEY,
    FIRST_NAME  VARCHAR(255),
    LAST_NAME   VARCHAR(255),
    BIRTH_DATE  VARCHAR(64),
    EMAIL       VARCHAR(255),
    CITY        VARCHAR(255),
    NOTES       VARCHAR(1024)
);
GO

IF NOT EXISTS (SELECT 1 FROM sys.indexes WHERE name = 'idx_person_first_name')
    CREATE INDEX idx_person_first_name ON dbo.PERSON (FIRST_NAME);
IF NOT EXISTS (SELECT 1 FROM sys.indexes WHERE name = 'idx_person_last_name')
    CREATE INDEX idx_person_last_name ON dbo.PERSON (LAST_NAME);
GO
