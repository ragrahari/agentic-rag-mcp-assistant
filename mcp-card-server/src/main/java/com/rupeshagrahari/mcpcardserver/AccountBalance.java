package com.rupeshagrahari.mcpcardserver;

import java.math.BigDecimal;

public record AccountBalance(String accountId, String tenantId, BigDecimal balance, String currency) {
}
