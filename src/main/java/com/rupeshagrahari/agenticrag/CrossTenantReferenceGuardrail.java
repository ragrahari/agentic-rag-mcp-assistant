package com.rupeshagrahari.agenticrag;

import java.util.List;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * M4 part 4: the cross-tenant-reference guardrail. Deterministic, sibling to the M4a
 * tenant-active guardrail in {@link TenantRegistryService} -- runs immediately after it
 * and before the router, no LLM involved.
 * <p>
 * Closes the M2-deferred narration gap (see PLAN.md / CLAUDE.md): retrieval is already
 * correctly scoped to the caller's own tenant, but nothing previously stopped the model
 * from narrating about a different, named tenant using the caller's own
 * (correctly-scoped) data -- e.g. an acme caller asking "what is globex's spend limit"
 * gets acme's figure mislabeled as globex's. No data crosses tenants, but the narration
 * does. This check denies before generation ever starts, rather than trying to police
 * the model's output after the fact.
 */
@Service
public class CrossTenantReferenceGuardrail {

    private static final Logger log = LoggerFactory.getLogger(CrossTenantReferenceGuardrail.class);

    private final TenantRegistryService tenantRegistryService;

    public CrossTenantReferenceGuardrail(TenantRegistryService tenantRegistryService) {
        this.tenantRegistryService = tenantRegistryService;
    }

    // Only matches registered tenant names -- not an open-ended "detect any company
    // name" NLP problem, which would be unbounded and false-positive-prone. The residual
    // false-positive surface is bounded to tenant-naming choices: a tenant registered
    // with a name that's also common domain vocabulary (e.g. "card") would make that
    // word a blocked reference for every OTHER tenant's messages. That's a
    // tenant-onboarding/naming concern this check can't solve on its own -- word-boundary
    // matching only rules out *substring* false positives (a tenant named "cat" won't
    // match "category"), not whole-word collisions with common vocabulary. Worth keeping
    // in mind when choosing tenant ids.
    public void requireNoCrossTenantReference(String tenantId, String message, String correlationId) {
        List<String> otherTenantIds = tenantRegistryService.listOtherTenantIds(tenantId);

        for (String otherTenantId : otherTenantIds) {
            if (referencesTenant(message, otherTenantId)) {
                // referencedTenant is logged here (operator-facing, internal) but never
                // returned to the caller -- the HTTP-facing reason stays generic, same
                // discipline as the tenant_active check's denial message.
                log.info(
                        "event=guardrail_outcome correlationId={} tenantId={} check={} outcome={} reason={} referencedTenant={}",
                        correlationId, tenantId, "cross_tenant_reference", "DENIED",
                        "Requests referencing another organization are not permitted", otherTenantId);
                throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                        "Requests referencing another organization are not permitted");
            }
        }

        log.info("event=guardrail_outcome correlationId={} tenantId={} check={} outcome={}", correlationId,
                tenantId, "cross_tenant_reference", "ALLOWED");
    }

    // Case-insensitive whole-word match: Pattern.quote treats the tenant id as a literal
    // string, not a regex (defensive against tenant ids containing regex metacharacters),
    // and \b on both sides requires it to stand alone as a word/token -- "globex" matches
    // "what is globex's spend limit" (the apostrophe is a non-word character, so the
    // boundary still holds immediately after "globex") but would not match inside a
    // larger word like "globexico".
    private boolean referencesTenant(String message, String otherTenantId) {
        Pattern pattern = Pattern.compile("\\b" + Pattern.quote(otherTenantId) + "\\b", Pattern.CASE_INSENSITIVE);
        return pattern.matcher(message).find();
    }

}
