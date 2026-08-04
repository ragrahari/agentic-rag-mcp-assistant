package com.rupeshagrahari.mcpcardserver;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

@Component
public class CardBalanceTools {

    private final AccountService accountService;

    public CardBalanceTools(AccountService accountService) {
        this.accountService = accountService;
    }

    @Tool(description = "Get the current balance for a corporate card or account. "
            + "Requires the account/card id and the tenant id that owns it.")
    public AccountBalance getCardBalance(
            @ToolParam(description = "The account or card id, e.g. CARD-1001") String accountId,
            @ToolParam(description = "The id of the tenant that owns this account") String tenantId) {
        return accountService.getBalance(tenantId, accountId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "No account '" + accountId + "' found for tenant '" + tenantId + "'"));
    }

}
