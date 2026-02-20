package com.shotaroi.bank.transfer.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

public record TransferRequest(
        @NotNull
        Long fromAccountId,

        @NotNull
        Long toAccountId,

        @NotNull @DecimalMin("0.01")
        BigDecimal amount,

        @NotNull @Size(min = 3, max = 3)
        String currency,

        String reference
) {
}
