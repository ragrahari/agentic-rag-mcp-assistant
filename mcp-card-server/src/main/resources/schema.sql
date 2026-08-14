CREATE TABLE IF NOT EXISTS accounts (
    account_id VARCHAR(64)    NOT NULL,
    tenant_id  VARCHAR(64)    NOT NULL,
    balance    NUMERIC(12, 2) NOT NULL,
    currency   VARCHAR(3)     NOT NULL,
    PRIMARY KEY (account_id, tenant_id)
);

CREATE TABLE IF NOT EXISTS transactions (
    id         VARCHAR(64)    NOT NULL,
    tenant_id  VARCHAR(64)    NOT NULL,
    account_id VARCHAR(64)    NOT NULL,
    amount     NUMERIC(12, 2) NOT NULL,
    merchant   VARCHAR(255)   NOT NULL,
    txn_date   DATE           NOT NULL,
    status     VARCHAR(20)    NOT NULL,
    PRIMARY KEY (id, tenant_id)
);

-- Same physical table as the main app's src/main/resources/schema.sql -- both
-- processes share one Postgres instance, either may start first, so both defensively
-- CREATE TABLE IF NOT EXISTS. This is the single authoritative tenant registry the main
-- app's /chat guardrail reads (M4 part 1).
CREATE TABLE IF NOT EXISTS tenants (
    tenant_id  VARCHAR(64) PRIMARY KEY,
    status     VARCHAR(10) NOT NULL CHECK (status IN ('ACTIVE', 'INACTIVE')),
    created_at TIMESTAMP   NOT NULL DEFAULT now()
);
