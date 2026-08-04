package com.rupeshagrahari.mcpcardserver;

import java.util.Optional;

/**
 * Seam between the MCP tool layer and the actual data backend. The tool calls only this
 * interface, so swapping {@link PostgresAccountService} for a real backend later is a
 * one-class change.
 */
public interface AccountService {

    Optional<AccountBalance> getBalance(String tenantId, String accountId);

}
