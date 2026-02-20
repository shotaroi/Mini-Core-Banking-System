package com.shotaroi.bank.ledger;

import com.shotaroi.bank.account.Account;
import com.shotaroi.bank.account.AccountService;
import com.shotaroi.bank.common.exceptions.ResourceNotFoundException;
import com.shotaroi.bank.ledger.dto.LedgerEntryResponse;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

import java.time.Instant;

@Service
public class LedgerService {

    private final LedgerRepository ledgerRepository;
    private final AccountService accountService;

    public LedgerService(LedgerRepository ledgerRepository, AccountService accountService) {
        this.ledgerRepository = ledgerRepository;
        this.accountService = accountService;
    }

    public Page<LedgerEntryResponse> getLedger(Long accountId, Long customerId, Instant from, Instant to, int page, int size) {
        Account account = accountService.findById(accountId);
        accountService.verifyOwnership(account, customerId);

        Pageable pageable = PageRequest.of(page, size);
        Page<LedgerEntry> entries;
        if (from != null || to != null) {
            Instant fromDate = from != null ? from : Instant.EPOCH;
            Instant toDate = to != null ? to : Instant.now();
            entries = ledgerRepository.findByAccountIdAndCreatedAtBetweenOrderByCreatedAtDesc(accountId, fromDate, toDate, pageable);
        } else {
            entries = ledgerRepository.findByAccountIdOrderByCreatedAtDesc(accountId, pageable);
        }
        return entries.map(LedgerEntryResponse::from);
    }
}
