package com.example.bank.audit.dto;

import com.example.bank.audit.AuditLog;

import java.time.Instant;

public record AuditLogResponse(
        Long id,
        Long actorCustomerId,
        String action,
        String details,
        Instant createdAt
) {
    public static AuditLogResponse from(AuditLog log) {
        return new AuditLogResponse(
                log.getId(),
                log.getActorCustomerId(),
                log.getAction(),
                log.getDetails(),
                log.getCreatedAt()
        );
    }
}
