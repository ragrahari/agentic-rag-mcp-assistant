package com.rupeshagrahari.agenticrag;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * The single authoritative registry of known tenants (M4 part 1). Backs the
 * deterministic pre-chat guardrail in {@link ChatController}: tenant existence + status
 * only, no LLM involved.
 */
@Service
public class TenantRegistryService {

    private static final Logger log = LoggerFactory.getLogger(TenantRegistryService.class);

    private final JdbcTemplate jdbcTemplate;

    public TenantRegistryService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    // Single atomic upsert: Postgres resolves the tenant_id primary-key conflict at the
    // row level inside one statement, so two concurrent ingests registering the same new
    // tenant can't race into a duplicate-key error or a lost update -- both converge on
    // one ACTIVE row. created_at is intentionally omitted from the DO UPDATE SET clause
    // so re-registering (including reactivating a previously deprovisioned tenant) never
    // overwrites the original registration timestamp.
    public void registerActive(String tenantId) {
        jdbcTemplate.update(
                "INSERT INTO tenants (tenant_id, status) VALUES (?, 'ACTIVE') "
                        + "ON CONFLICT (tenant_id) DO UPDATE SET status = 'ACTIVE'",
                tenantId);
    }

    // Guardrail check: one SELECT keyed on the tenant_id primary key -- an indexed point
    // lookup, not a scan. Unknown tenants and deprovisioned (INACTIVE) tenants are denied
    // identically: same 403, same reason string. This is an access gate, not a resource
    // lookup, so the denial deliberately doesn't reveal whether a tenant never existed or
    // existed and was deprovisioned -- distinguishing the two in the message would leak
    // exactly the thing the generic wording is meant to hide.
    public void requireActiveTenant(String tenantId, String correlationId) {
        List<String> statuses = jdbcTemplate.query(
                "SELECT status FROM tenants WHERE tenant_id = ?",
                (rs, rowNum) -> rs.getString("status"),
                tenantId);

        boolean active = !statuses.isEmpty() && TenantStatus.ACTIVE.name().equals(statuses.get(0));
        if (!active) {
            log.info("event=guardrail_outcome correlationId={} tenantId={} check={} outcome={} reason={}",
                    correlationId, tenantId, "tenant_active", "DENIED", "Tenant not recognized or inactive");
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Tenant not recognized or inactive");
        }
        log.info("event=guardrail_outcome correlationId={} tenantId={} check={} outcome={}", correlationId, tenantId,
                "tenant_active", "ALLOWED");
    }

    // Full scan of the tenants table, excluding the caller's own id -- used only by the
    // cross-tenant-reference guardrail (M4 part 4) to scan the user's message against
    // every OTHER known tenant name. Unlike requireActiveTenant() above, this is
    // deliberately not a single indexed lookup -- it needs the whole set of other names,
    // not one row. Acceptable as a scan: this table is small by construction (one row
    // per onboarded tenant), not a per-document/per-chunk table.
    public List<String> listOtherTenantIds(String tenantId) {
        return jdbcTemplate.query("SELECT tenant_id FROM tenants WHERE tenant_id <> ?",
                (rs, rowNum) -> rs.getString("tenant_id"), tenantId);
    }

}
