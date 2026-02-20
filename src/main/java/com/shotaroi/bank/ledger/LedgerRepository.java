package com.shotaroi.bank.ledger;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;

public interface LedgerRepository extends JpaRepository<LedgerEntry, Long> {

    Page<LedgerEntry> findByAccountIdAndCreatedAtBetweenOrderByCreatedAtDesc(
            Long accountId,
            Instant from,
            Instant to,
            Pageable pageable);

    Page<LedgerEntry> findByAccountIdOrderByCreatedAtDesc(Long accountId, Pageable pageable);
}
