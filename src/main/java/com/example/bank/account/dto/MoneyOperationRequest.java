package com.example.bank.account.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

public record MoneyOperationRequest(
        @NotNull @DecimalMin("0.01")
        BigDecimal amount,

        @NotBlank @Size(min = 3, max = 3)
        String currency,

        String reference
) {
}
