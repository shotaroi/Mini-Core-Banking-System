package com.shotaroi.bank.common.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
        
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ApiError(
        String timestamp,
        int status, // HTTP status code
        String error,
        String message,
        String path
) {
}
