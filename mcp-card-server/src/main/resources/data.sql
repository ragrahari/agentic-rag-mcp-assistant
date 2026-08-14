-- CARD-1001 exists under both tenants with deliberately different balances, so a
-- cross-tenant test can assert the same account id resolves to different, isolated data.
INSERT INTO accounts (account_id, tenant_id, balance, currency) VALUES
    ('CARD-1001', 'acme',   4250.75, 'USD'),
    ('CARD-1002', 'acme',    980.00, 'USD'),
    ('CARD-1001', 'globex', 12500.00, 'USD'),
    ('CARD-2001', 'globex',  3125.40, 'USD')
ON CONFLICT (account_id, tenant_id) DO NOTHING;

INSERT INTO transactions (id, tenant_id, account_id, amount, merchant, txn_date, status) VALUES
    ('TXN-9001', 'acme',   'CARD-1001', 340.00, 'The Grill House',   '2026-07-28', 'POSTED'),
    ('TXN-9002', 'acme',   'CARD-1001',  89.50, 'Delta Airlines',    '2026-07-25', 'POSTED'),
    ('TXN-9003', 'acme',   'CARD-1002',  22.10, 'Uber',              '2026-08-01', 'PENDING'),
    ('TXN-9101', 'globex', 'CARD-1001', 1200.00, 'Marriott Hotels',  '2026-07-30', 'POSTED'),
    ('TXN-9102', 'globex', 'CARD-2001',  64.25, 'Uber',              '2026-08-02', 'POSTED')
ON CONFLICT (id, tenant_id) DO NOTHING;

-- Registers acme/globex as ACTIVE tenants alongside their account/transaction seed data
-- (M4 part 1). ON CONFLICT DO UPDATE (not DO NOTHING) so restarting this server
-- reactivates a tenant that was deprovisioned via the main app -- consistent with
-- register-on-write always meaning ACTIVE. created_at is left untouched on conflict
-- since it's omitted from the SET clause.
INSERT INTO tenants (tenant_id, status) VALUES
    ('acme', 'ACTIVE'),
    ('globex', 'ACTIVE')
ON CONFLICT (tenant_id) DO UPDATE SET status = 'ACTIVE';
