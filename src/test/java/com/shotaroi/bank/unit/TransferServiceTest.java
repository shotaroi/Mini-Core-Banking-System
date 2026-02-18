package com.shotaroi.bank.unit;

import com.shotaroi.bank.account.Account;
import com.shotaroi.bank.account.AccountRepository;
import com.shotaroi.bank.audit.AuditService;
import com.shotaroi.bank.common.exceptions.IdempotencyConflictException;
import com.shotaroi.bank.common.exceptions.InsufficientFundsException;
import com.shotaroi.bank.common.exceptions.ResourceNotFoundException;
import com.shotaroi.bank.ledger.LedgerEntry;
import com.shotaroi.bank.ledger.LedgerRepository;
import com.shotaroi.bank.transfer.Transfer;
import com.shotaroi.bank.transfer.TransferRepository;
import com.shotaroi.bank.transfer.TransferService;
import com.shotaroi.bank.transfer.dto.TransferRequest;
import com.shotaroi.bank.transfer.dto.TransferResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class TransferServiceTest {

    @Mock
    private TransferRepository transferRepository;
    @Mock
    private AccountRepository accountRepository;
    @Mock
    private LedgerRepository ledgerRepository;
    @Mock
    private AuditService auditService;

    private TransferService transferService;

    private Account fromAccount;
    private Account toAccount;
    private TransferRequest request;

    @BeforeEach
    void setUp() {
        transferService = new TransferService(transferRepository, accountRepository, ledgerRepository, auditService);

        fromAccount = new Account();
        fromAccount.setId(1L);
        fromAccount.setCustomerId(100L);
        fromAccount.setBalance(new BigDecimal("100.00"));
        fromAccount.setCurrency("SEK");

        toAccount = new Account();
        toAccount.setId(2L);
        toAccount.setCustomerId(200L);
        toAccount.setBalance(new BigDecimal("50.00"));
        toAccount.setCurrency("SEK");

        request = new TransferRequest(1L, 2L, new BigDecimal("20.00"), "SEK", "test ref");
    }

    @Test
    void executeTransfer_requiresIdempotencyKey() {
        assertThatThrownBy(() -> transferService.executeTransfer(null, 100L, request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Idempotency-Key");
    }

    @Test
    void executeTransfer_rejectsInvalidAmount() {
        TransferRequest invalidRequest = new TransferRequest(1L, 2L, BigDecimal.ZERO, "SEK", null);
        assertThatThrownBy(() -> transferService.executeTransfer("key-1", 100L, invalidRequest))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void executeTransfer_rejectsSameAccount() {
        when(accountRepository.findByIdWithLock(1L)).thenReturn(Optional.of(fromAccount));

        TransferRequest sameAccountRequest = new TransferRequest(1L, 1L, new BigDecimal("10.00"), "SEK", null);
        assertThatThrownBy(() -> transferService.executeTransfer("key-1", 100L, sameAccountRequest))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("different");
    }

    @Test
    void executeTransfer_rejectsNonOwner() {
        when(accountRepository.findByIdWithLock(1L)).thenReturn(Optional.of(fromAccount));
        when(accountRepository.findByIdWithLock(2L)).thenReturn(Optional.of(toAccount));

        assertThatThrownBy(() -> transferService.executeTransfer("key-1", 999L, request))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void executeTransfer_rejectsInsufficientFunds() {
        when(accountRepository.findByIdWithLock(1L)).thenReturn(Optional.of(fromAccount));
        when(accountRepository.findByIdWithLock(2L)).thenReturn(Optional.of(toAccount));

        TransferRequest largeRequest = new TransferRequest(1L, 2L, new BigDecimal("200.00"), "SEK", null);
        assertThatThrownBy(() -> transferService.executeTransfer("key-1", 100L, largeRequest))
                .isInstanceOf(InsufficientFundsException.class)
                .hasMessageContaining("Insufficient funds");
    }

    @Test
    void executeTransfer_rejectsCurrencyMismatch() {
        toAccount.setCurrency("USD");
        when(accountRepository.findByIdWithLock(1L)).thenReturn(Optional.of(fromAccount));
        when(accountRepository.findByIdWithLock(2L)).thenReturn(Optional.of(toAccount));

        assertThatThrownBy(() -> transferService.executeTransfer("key-1", 100L, request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Currency");
    }

    @Test
    void executeTransfer_idempotentSamePayloadReturnsExisting() {
        Transfer existing = new Transfer();
        existing.setId(42L);
        existing.setFromAccountId(1L);
        existing.setToAccountId(2L);
        existing.setAmount(new BigDecimal("20.00"));
        existing.setCurrency("SEK");
        existing.setStatus(Transfer.TransferStatus.SUCCESS);
        existing.setCreatedAt(java.time.Instant.now());

        when(transferRepository.findByIdempotencyKey("key-1")).thenReturn(Optional.of(existing));

        TransferResponse response = transferService.executeTransfer("key-1", 100L, request);

        assertThat(response.transferId()).isEqualTo(42L);
        assertThat(response.status()).isEqualTo(Transfer.TransferStatus.SUCCESS);
        verify(accountRepository, never()).save(any());
    }

    @Test
    void executeTransfer_idempotentDifferentPayloadReturns409() {
        Transfer existing = new Transfer();
        existing.setId(42L);
        existing.setFromAccountId(1L);
        existing.setToAccountId(2L);
        existing.setAmount(new BigDecimal("15.00"));
        existing.setCurrency("SEK");
        existing.setStatus(Transfer.TransferStatus.SUCCESS);

        when(transferRepository.findByIdempotencyKey("key-1")).thenReturn(Optional.of(existing));

        assertThatThrownBy(() -> transferService.executeTransfer("key-1", 100L, request))
                .isInstanceOf(IdempotencyConflictException.class)
                .hasMessageContaining("different payload");
    }
}
