package com.example.bank.account.dto;

import java.math.BigDecimal;
import java.time.Instant;

public record AccountResponse(
        Long id,
        String iban,
        String currency,
        BigDecimal balance,
        Instant createdAt
) {
}
