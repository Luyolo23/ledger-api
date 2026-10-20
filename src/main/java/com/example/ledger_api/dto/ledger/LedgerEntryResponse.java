package com.example.ledger_api.dto.ledger;

import java.time.Instant;
import java.util.UUID;

public record LedgerEntryResponse(
        UUID entryId,
        UUID transferId,
        UUID accountId,
        String direction,
        long amountMinor,
        String currency,
        Instant createdAt
) {
}
