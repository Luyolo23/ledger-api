package com.example.ledger_api.web;

import com.example.ledger_api.dto.transfer.CreateTransferRequest;
import com.example.ledger_api.dto.transfer.TransferResponse;
import com.example.ledger_api.service.*;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.*;
import org.springframework.security.core.Authentication;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/transfers")
@Validated
@Tag(name = "Transfers")
public class TransferController {

    private final TransferService transferService;
    private final UserService userService;

    public TransferController(TransferService transferService, UserService userService) {
        this.transferService = transferService;
        this.userService = userService;
    }

    @PostMapping
    @Operation(
            summary = "Create a double-entry transfer",
            description = "Requires an Idempotency-Key header. Retrying the same request with the same key "
                    + "returns the original transfer. Reusing the key with a different payload returns 409."
    )
    public ResponseEntity<TransferResponse> transfer(@RequestHeader("Idempotency-Key") @NotBlank String idempotencyKey,
            @Valid @RequestBody CreateTransferRequest request,
            Authentication authentication
    ) {
        UUID userId = userService.requireIdByEmail(authentication.getName());
        TransferExecution execution = transferService.transfer(userId, idempotencyKey, request);

        HttpStatus status = execution.replayed() ? HttpStatus.OK : HttpStatus.CREATED;
        return ResponseEntity.status(status).body(execution.transfer());
    }
}