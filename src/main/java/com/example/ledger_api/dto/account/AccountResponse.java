package com.example.ledger_api.dto.account;

import java.time.Instant;
import java.util.UUID;

public record AccountResponse(
        UUID id,
        String name,
        String currency,
        long balanceMinor,
        Instant createdAt
) {
}
