package com.rupeshagrahari.mcpcardserver;

import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class PostgresAccountService implements AccountService {

    private final JdbcTemplate jdbcTemplate;

    public PostgresAccountService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public Optional<AccountBalance> getBalance(String tenantId, String accountId) {
        List<AccountBalance> results = jdbcTemplate.query(
                "SELECT account_id, tenant_id, balance, currency FROM accounts WHERE account_id = ? AND tenant_id = ?",
                (rs, rowNum) -> new AccountBalance(rs.getString("account_id"), rs.getString("tenant_id"),
                        rs.getBigDecimal("balance"), rs.getString("currency")),
                accountId, tenantId);
        return results.stream().findFirst();
    }

}
