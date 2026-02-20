package com.shotaroi.bank.transfer.dto;

import com.shotaroi.bank.transfer.Transfer;

import java.time.Instant;

public record TransferResponse(
        Long transferId,
        Transfer.TransferStatus status,
        Instant createdAt
) {
    public static TransferResponse from(Transfer transfer) {
        return new TransferResponse(
                transfer.getId(),
                transfer.getStatus(),
                transfer.getCreatedAt()
        );
    }
}
