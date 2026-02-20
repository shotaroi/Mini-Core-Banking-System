package com.shotaroi.bank.transfer;

import com.shotaroi.bank.security.AuthenticatedUser;
import com.shotaroi.bank.transfer.dto.TransferRequest;
import com.shotaroi.bank.transfer.dto.TransferResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/transfers")
public class TransferController {

    private final TransferService transferService;

    public TransferController(TransferService transferService) {
        this.transferService = transferService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public TransferResponse createTransfer(
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @AuthenticationPrincipal AuthenticatedUser user,
            @Valid @RequestBody TransferRequest request) {
        return transferService.executeTransfer(idempotencyKey, user.id(), request);
    }
}
