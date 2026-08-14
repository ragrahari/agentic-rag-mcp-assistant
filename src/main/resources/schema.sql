-- Tenant registry (M4 part 1): the single authoritative source of which tenants exist
-- and whether they're allowed to make requests. Same physical table as
-- mcp-card-server/src/main/resources/schema.sql -- both processes share one Postgres
-- instance and either may start first, so both defensively CREATE TABLE IF NOT EXISTS.
--
-- status is an application-level enum (see TenantStatus.java), stored as VARCHAR with a
-- CHECK constraint rather than a native Postgres ENUM type -- avoids JDBC driver type
-- casting friction on plain `?` parameters for no real benefit at this scale.
CREATE TABLE IF NOT EXISTS tenants (
    tenant_id  VARCHAR(64) PRIMARY KEY,
    status     VARCHAR(10) NOT NULL CHECK (status IN ('ACTIVE', 'INACTIVE')),
    created_at TIMESTAMP   NOT NULL DEFAULT now()
);
