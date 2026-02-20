package com.shotaroi.bank.transfer;

import com.shotaroi.bank.account.Account;
import com.shotaroi.bank.account.AccountRepository;
import com.shotaroi.bank.account.AccountService;
import com.shotaroi.bank.audit.AuditService;
import com.shotaroi.bank.common.exceptions.IdempotencyConflictException;
import com.shotaroi.bank.common.exceptions.InsufficientFundsException;
import com.shotaroi.bank.common.exceptions.ResourceNotFoundException;
import com.shotaroi.bank.ledger.LedgerEntry;
import com.shotaroi.bank.ledger.LedgerRepository;
import com.shotaroi.bank.transfer.dto.TransferRequest;
import com.shotaroi.bank.transfer.dto.TransferResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.Objects;

@Service
public class TransferService {

    private static final Logger log = LoggerFactory.getLogger(TransferService.class);
    private static final int MAX_RETRIES = 3;

    private final TransferRepository transferRepository;
    private final AccountRepository accountRepository;
    private final LedgerRepository ledgerRepository;
    private final AuditService auditService;

    public TransferService(TransferRepository transferRepository,
                            AccountRepository accountRepository,
                            LedgerRepository ledgerRepository,
                            AuditService auditService) {
        this.transferRepository = transferRepository;
        this.accountRepository = accountRepository;
        this.ledgerRepository = ledgerRepository;
        this.auditService = auditService;
    }

    @Transactional
    public TransferResponse executeTransfer(String idempotencyKey, Long initiatorCustomerId, TransferRequest request) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new IllegalArgumentException("Idempotency-Key header is required");
        }

        var existing = transferRepository.findByIdempotencyKey(idempotencyKey);
        if (existing.isPresent()) {
            Transfer t = existing.get();
            if (samePayload(t, request)) {
                log.info("Idempotent replay: idempotencyKey={}, transferId={}", idempotencyKey, t.getId());
                return TransferResponse.from(t);
            }
            throw new IdempotencyConflictException("Idempotency key already used with different payload");
        }

        validateTransferRequest(request);

        Account fromAccount = accountRepository.findByIdWithLock(request.fromAccountId())
                .orElseThrow(() -> new ResourceNotFoundException("Source account not found: " + request.fromAccountId()));
        Account toAccount = accountRepository.findByIdWithLock(request.toAccountId())
                .orElseThrow(() -> new ResourceNotFoundException("Destination account not found: " + request.toAccountId()));

        if (!fromAccount.getCustomerId().equals(initiatorCustomerId)) {
            throw new ResourceNotFoundException("Source account not found: " + request.fromAccountId());
        }

        if (Objects.equals(fromAccount.getId(), toAccount.getId())) {
            throw new IllegalArgumentException("Source and destination accounts must be different");
        }

        if (!fromAccount.getCurrency().equals(request.currency()) || !toAccount.getCurrency().equals(request.currency())) {
            throw new IllegalArgumentException("Currency must match both accounts");
        }

        BigDecimal amount = request.amount().setScale(2, java.math.RoundingMode.HALF_UP);
        if (fromAccount.getBalance().compareTo(amount) < 0) {
            log.warn("Transfer failed - insufficient funds: fromAccountId={}, amount={}", fromAccount.getId(), amount);
            throw new InsufficientFundsException("Insufficient funds. Balance: " + fromAccount.getBalance() + " " + fromAccount.getCurrency());
        }

        ObjectOptimisticLockingFailureException lastException = null;
        for (int attempt = 1; attempt <= MAX_RETRIES; attempt++) {
            try {
                return doTransfer(fromAccount, toAccount, amount, request.currency(), request.reference(), idempotencyKey, initiatorCustomerId);
            } catch (ObjectOptimisticLockingFailureException e) {
                lastException = e;
                log.warn("Optimistic lock failure on transfer, attempt {}/{}: {}", attempt, MAX_RETRIES, e.getMessage());
                fromAccount = accountRepository.findByIdWithLock(request.fromAccountId()).orElseThrow();
                toAccount = accountRepository.findByIdWithLock(request.toAccountId()).orElseThrow();
                if (fromAccount.getBalance().compareTo(amount) < 0) {
                    throw new InsufficientFundsException("Insufficient funds on retry");
                }
            }
        }
        throw lastException != null ? lastException : new IllegalStateException("Transfer failed");
    }

    @Transactional
    protected TransferResponse doTransfer(Account fromAccount, Account toAccount, BigDecimal amount, String currency, String reference, String idempotencyKey, Long initiatorCustomerId) {
        fromAccount.setBalance(fromAccount.getBalance().subtract(amount));
        toAccount.setBalance(toAccount.getBalance().add(amount));
        accountRepository.save(fromAccount);
        accountRepository.save(toAccount);

        Transfer transfer = new Transfer();
        transfer.setFromAccountId(fromAccount.getId());
        transfer.setToAccountId(toAccount.getId());
        transfer.setAmount(amount);
        transfer.setCurrency(currency);
        transfer.setStatus(Transfer.TransferStatus.SUCCESS);
        transfer.setIdempotencyKey(idempotencyKey);
        transfer.setReference(reference);
        transfer = transferRepository.save(transfer);

        LedgerEntry outEntry = new LedgerEntry();
        outEntry.setAccountId(fromAccount.getId());
        outEntry.setType(LedgerEntry.EntryType.TRANSFER_OUT);
        outEntry.setAmount(amount);
        outEntry.setCurrency(currency);
        outEntry.setCounterpartyAccountId(toAccount.getId());
        outEntry.setReference(reference);
        ledgerRepository.save(outEntry);

        LedgerEntry inEntry = new LedgerEntry();
        inEntry.setAccountId(toAccount.getId());
        inEntry.setType(LedgerEntry.EntryType.TRANSFER_IN);
        inEntry.setAmount(amount);
        inEntry.setCurrency(currency);
        inEntry.setCounterpartyAccountId(fromAccount.getId());
        inEntry.setReference(reference);
        ledgerRepository.save(inEntry);

        auditService.log(initiatorCustomerId, "TRANSFER", "transferId=%d, from=%d, to=%d, amount=%s %s".formatted(transfer.getId(), fromAccount.getId(), toAccount.getId(), amount, currency));
        log.info("Transfer completed: id={}, from={}, to={}, amount={} {}", transfer.getId(), fromAccount.getId(), toAccount.getId(), amount, currency);
        return TransferResponse.from(transfer);
    }

    private boolean samePayload(Transfer t, TransferRequest request) {
        return Objects.equals(t.getFromAccountId(), request.fromAccountId())
                && Objects.equals(t.getToAccountId(), request.toAccountId())
                && t.getAmount().compareTo(request.amount()) == 0
                && Objects.equals(t.getCurrency(), request.currency());
    }

    private void validateTransferRequest(TransferRequest request) {
        if (request.amount() == null || request.amount().compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("Amount must be positive");
        }
        if (request.fromAccountId() == null || request.toAccountId() == null) {
            throw new IllegalArgumentException("Both fromAccountId and toAccountId are required");
        }
    }
}
