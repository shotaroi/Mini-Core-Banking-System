package com.shotaroi.bank.ledger.dto;

import com.shotaroi.bank.ledger.LedgerEntry;

import java.math.BigDecimal;
import java.time.Instant;

public record LedgerEntryResponse(
        Long id,
        LedgerEntry.EntryType type,
        BigDecimal amount,
        String currency,
        Long counterpartyAccountId,
        String reference,
        Instant createdAt
) {
    public static LedgerEntryResponse from(LedgerEntry entry) {
        return new LedgerEntryResponse(
                entry.getId(),
                entry.getType(),
                entry.getAmount(),
                entry.getCurrency(),
                entry.getCounterpartyAccountId(),
                entry.getReference(),
                entry.getCreatedAt()
        );
    }
}
