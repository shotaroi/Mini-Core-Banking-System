package com.shotaroi.bank.account;

import com.shotaroi.bank.account.dto.AccountResponse;
import com.shotaroi.bank.account.dto.CreateAccountRequest;
import com.shotaroi.bank.account.dto.MoneyOperationRequest;
import com.shotaroi.bank.security.AuthenticatedUser;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/accounts")
public class AccountController {

    private final AccountService accountService;

    public AccountController(AccountService accountService) {
        this.accountService = accountService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public AccountResponse createAccount(@AuthenticationPrincipal AuthenticatedUser user,
                                         @Valid @RequestBody CreateAccountRequest request) {
        Account account = accountService.createAccount(user.id(), request);
        return AccountService.toResponse(account);
    }

    @GetMapping
    public List<AccountResponse> listAccounts(@AuthenticationPrincipal AuthenticatedUser user) {
        return accountService.findAccountsByCustomerId(user.id()).stream()
                .map(AccountService::toResponse)
                .toList();
    }

    @GetMapping("/{id}")
    public AccountResponse getAccount(@AuthenticationPrincipal AuthenticatedUser user,
                                     @PathVariable Long id) {
        Account account = accountService.findById(id);
        accountService.verifyOwnership(account, user.id());
        return AccountService.toResponse(account);
    }

    @PostMapping("/{id}/deposit")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deposit(@AuthenticationPrincipal AuthenticatedUser user,
                        @PathVariable Long id,
                        @Valid @RequestBody MoneyOperationRequest request) {
        accountService.deposit(id, user.id(), request);
    }

    @PostMapping("/{id}/withdraw")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void withdraw(@AuthenticationPrincipal AuthenticatedUser user,
                         @PathVariable Long id,
                         @Valid @RequestBody MoneyOperationRequest request) {
        accountService.withdraw(id, user.id(), request);
    }
}
