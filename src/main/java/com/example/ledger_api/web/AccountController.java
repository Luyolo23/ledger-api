package com.example.ledger_api.web;

import com.example.ledger_api.dto.account.AccountResponse;
import com.example.ledger_api.dto.account.CreateAccountRequest;
import com.example.ledger_api.dto.common.PageResponse;
import com.example.ledger_api.dto.ledger.LedgerEntryResponse;
import com.example.ledger_api.service.AccountService;
import com.example.ledger_api.service.UserService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/accounts")
@Validated
@Tag(name = "Accounts")
public class AccountController {

    private final AccountService accountService;
    private final UserService userService;

    public AccountController(AccountService accountService, UserService userService) {
        this.accountService = accountService;
        this.userService = userService;
    }

    @PostMapping
    @Operation(summary = "Create an account for the authenticated user")
    public ResponseEntity<AccountResponse> create(
            @Valid @RequestBody CreateAccountRequest request,
            Authentication authentication
    ) {
        UUID userId = userService.requireIdByEmail(authentication.getName());
        AccountResponse response = accountService.create(userId, request);

        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @GetMapping
    @Operation(summary = "List the authenticated user's accounts with derived balances")
    public List<AccountResponse> list(Authentication authentication) {
        UUID userId = userService.requireIdByEmail(authentication.getName());
        return accountService.list(userId);
    }

    @GetMapping("/{accountId}")
    @Operation(summary = "Get one account owned by the authenticated user")
    public AccountResponse get(
            @PathVariable UUID accountId,
            Authentication authentication
    ) {
        UUID userId = userService.requireIdByEmail(authentication.getName());
        return accountService.get(userId, accountId);
    }

    @GetMapping("/{accountId}/transactions")
    @Operation(summary = "Paginated transaction history with optional date filtering")
    public PageResponse<LedgerEntryResponse> history(
            @PathVariable UUID accountId,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
            Instant from,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
            Instant to,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size,
            Authentication authentication
    ) {
        UUID userId = userService.requireIdByEmail(authentication.getName());
        return PageResponse.from(accountService.history(userId, accountId, from, to, page, size));
    }
}