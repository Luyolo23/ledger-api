package com.example.ledger_api.dto.auth;

import java.time.Instant;
import java.util.UUID;

public record UserResponse(
        UUID id,
        String email,
        Instant createdAt
) {
}
