package com.example.ledger_api.dto.transfer;

import java.time.Instant;
import java.util.UUID;

public record TransferResponse(
        UUID transferId,
        UUID fromAccountId,
        UUID toAccountId,
        long amountMinor,
        String currency,
        Instant createdAt
) {
}
