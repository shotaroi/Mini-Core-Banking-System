package com.shotaroi.bank.customer.dto;

import jakarta.validation.constraints.NotBlank;

public record LoginRequest(
        @NotBlank
        String email,

        @NotBlank
        String password
) {
}
