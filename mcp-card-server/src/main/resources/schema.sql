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
