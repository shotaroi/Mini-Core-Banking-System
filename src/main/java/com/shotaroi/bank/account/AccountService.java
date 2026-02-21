package com.shotaroi.bank.account;

import com.shotaroi.bank.audit.AuditService;
import com.shotaroi.bank.common.exceptions.InsufficientFundsException;
import com.shotaroi.bank.common.exceptions.ResourceNotFoundException;
import com.shotaroi.bank.account.dto.AccountResponse;
import com.shotaroi.bank.account.dto.CreateAccountRequest;
import com.shotaroi.bank.account.dto.MoneyOperationRequest;
import com.shotaroi.bank.ledger.LedgerEntry;
import com.shotaroi.bank.ledger.LedgerRepository;
import com.shotaroi.bank.security.AuthenticatedUser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;

@Service
public class AccountService {

    private static final Logger log = LoggerFactory.getLogger(AccountService.class);
    private static final int MAX_IBAN_COLLISION_RETRIES = 3;

    private final AccountRepository accountRepository;
    private final LedgerRepository ledgerRepository;
    private final AuditService auditService;
    private final IbanGenerator ibanGenerator;

    public AccountService(AccountRepository accountRepository,
                          LedgerRepository ledgerRepository,
                          AuditService auditService,
                          IbanGenerator ibanGenerator) {
        this.accountRepository = accountRepository;
        this.ledgerRepository = ledgerRepository;
        this.auditService = auditService;
        this.ibanGenerator = ibanGenerator;
    }

    /**
     * Creates an account with a unique IBAN. Retries on IBAN collision: two concurrent threads
     * can generate the same IBAN and both pass existsByIban() before either commits (check-then-act
     * race). The DB unique constraint rejects the duplicate; we catch DataIntegrityViolationException,
     * retry with a fresh IBAN in a new transaction, and succeed on the next attempt.
     */
    public Account createAccount(Long customerId, CreateAccountRequest request) {
        for (int attempt = 1; attempt <= MAX_IBAN_COLLISION_RETRIES; attempt++) {
            try {
                return doCreateAccount(customerId, request);
            } catch (DataIntegrityViolationException e) {
                log.warn("IBAN collision on attempt {}/{}: retrying with new IBAN", attempt, MAX_IBAN_COLLISION_RETRIES);
                if (attempt == MAX_IBAN_COLLISION_RETRIES) {
                    throw new IllegalStateException("Failed to generate unique IBAN after " + MAX_IBAN_COLLISION_RETRIES + " attempts", e);
                }
            }
        }
        throw new IllegalStateException("Failed to create account");
    }

    @Transactional
    protected Account doCreateAccount(Long customerId, CreateAccountRequest request) {
        String iban;
        do {
            iban = ibanGenerator.generate();
        } while (accountRepository.existsByIban(iban));

        Account account = new Account();
        account.setCustomerId(customerId);
        account.setIban(iban);
        account.setCurrency(request.currency().toUpperCase());
        account.setBalance(BigDecimal.ZERO.setScale(2));
        account = accountRepository.save(account);
        log.info("Account created: id={}, iban={}, customerId={}", account.getId(), account.getIban(), customerId);
        return account;
    }

    public List<Account> findAccountsByCustomerId(Long customerId) {
        return accountRepository.findByCustomerId(customerId);
    }

    public Account findById(Long id) {
        return accountRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Account not found: " + id));
    }

    public void verifyOwnership(Account account, Long customerId) {
        if (!account.getCustomerId().equals(customerId)) {
            throw new ResourceNotFoundException("Account not found: " + account.getId());
        }
    }

    @Transactional
    public void deposit(Long accountId, Long customerId, MoneyOperationRequest request) {
        Account account = accountRepository.findByIdWithLock(accountId)
                .orElseThrow(() -> new ResourceNotFoundException("Account not found: " + accountId));
        verifyOwnership(account, customerId);
        validateAmount(request.amount(), request.currency());

        BigDecimal amount = request.amount().setScale(2, java.math.RoundingMode.HALF_UP);
        if (!account.getCurrency().equals(request.currency())) {
            throw new IllegalArgumentException("Currency mismatch: account has " + account.getCurrency());
        }

        account.setBalance(account.getBalance().add(amount));
        accountRepository.save(account);

        LedgerEntry entry = createLedgerEntry(account.getId(), LedgerEntry.EntryType.DEPOSIT, amount, account.getCurrency(), null, request.reference());
        ledgerRepository.save(entry);

        auditService.log(customerId, "DEPOSIT", "accountId=%d, amount=%s %s".formatted(accountId, amount, account.getCurrency()));
        log.info("Deposit: accountId={}, amount={} {}, newBalance={}", accountId, amount, account.getCurrency(), account.getBalance());
    }

    @Transactional
    public void withdraw(Long accountId, Long customerId, MoneyOperationRequest request) {
        Account account = accountRepository.findByIdWithLock(accountId)
                .orElseThrow(() -> new ResourceNotFoundException("Account not found: " + accountId));
        verifyOwnership(account, customerId);
        validateAmount(request.amount(), request.currency());

        BigDecimal amount = request.amount().setScale(2, java.math.RoundingMode.HALF_UP);
        if (!account.getCurrency().equals(request.currency())) {
            throw new IllegalArgumentException("Currency mismatch: account has " + account.getCurrency());
        }
        if (account.getBalance().compareTo(amount) < 0) {
            throw new InsufficientFundsException("Insufficient funds. Balance: " + account.getBalance() + " " + account.getCurrency());
        }

        account.setBalance(account.getBalance().subtract(amount));
        accountRepository.save(account);

        LedgerEntry entry = createLedgerEntry(account.getId(), LedgerEntry.EntryType.WITHDRAW, amount, account.getCurrency(), null, request.reference());
        ledgerRepository.save(entry);

        auditService.log(customerId, "WITHDRAW", "accountId=%d, amount=%s %s".formatted(accountId, amount, account.getCurrency()));
        log.info("Withdraw: accountId={}, amount={} {}, newBalance={}", accountId, amount, account.getCurrency(), account.getBalance());
    }

    public static AccountResponse toResponse(Account account) {
        return new AccountResponse(
                account.getId(),
                account.getIban(),
                account.getCurrency(),
                account.getBalance(),
                account.getCreatedAt()
        );
    }

    LedgerEntry createLedgerEntry(Long accountId, LedgerEntry.EntryType type, BigDecimal amount, String currency, Long counterpartyId, String reference) {
        LedgerEntry entry = new LedgerEntry();
        entry.setAccountId(accountId);
        entry.setType(type);
        entry.setAmount(amount);
        entry.setCurrency(currency);
        entry.setCounterpartyAccountId(counterpartyId);
        entry.setReference(reference);
        return entry;
    }

    private void validateAmount(BigDecimal amount, String currency) {
        if (amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("Amount must be positive");
        }
    }
}
