package com.example.ledger_api.service;

import com.example.ledger_api.domain.Account;
import com.example.ledger_api.domain.AppUser;
import com.example.ledger_api.domain.EntryDirection;
import com.example.ledger_api.domain.LedgerEntry;
import com.example.ledger_api.domain.Transfer;
import com.example.ledger_api.dto.transfer.CreateTransferRequest;
import com.example.ledger_api.dto.transfer.TransferResponse;
import com.example.ledger_api.exception.BadRequestException;
import com.example.ledger_api.exception.ConflictException;
import com.example.ledger_api.exception.InsufficientFundsException;
import com.example.ledger_api.exception.ResourceNotFoundException;
import com.example.ledger_api.repository.AccountRepository;
import com.example.ledger_api.repository.IdempotencyLockRepository;
import com.example.ledger_api.repository.LedgerEntryRepository;
import com.example.ledger_api.repository.TransferRepository;
import com.example.ledger_api.repository.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
public class TransferService {

    private final UserRepository userRepository;
    private final AccountRepository accountRepository;
    private final TransferRepository transferRepository;
    private final LedgerEntryRepository ledgerEntryRepository;
    private final IdempotencyLockRepository idempotencyLockRepository;

    public TransferService(
            UserRepository userRepository,
            AccountRepository accountRepository,
            TransferRepository transferRepository,
            LedgerEntryRepository ledgerEntryRepository,
            IdempotencyLockRepository idempotencyLockRepository
    ) {
        this.userRepository = userRepository;
        this.accountRepository = accountRepository;
        this.transferRepository = transferRepository;
        this.ledgerEntryRepository = ledgerEntryRepository;
        this.idempotencyLockRepository = idempotencyLockRepository;
    }

    @Transactional
    public TransferExecution transfer(
            UUID userId,
            String idempotencyKey,
            CreateTransferRequest request
    ) {
        String key = idempotencyKey == null ? null : idempotencyKey.trim();
        validateRequest(request, key);

        UUID fromId = request.fromAccountId();
        UUID toId = request.toAccountId();
        long amountMinor = request.amountMinor();

        // Serialize retries with the same user-scoped idempotency key before checking for an existing transfer.
        idempotencyLockRepository.lock(userId, key);

        Optional<Transfer> existingTransfer =
                transferRepository.findByUser_IdAndIdempotencyKey(userId, key);

        if (existingTransfer.isPresent()) {
            Transfer transfer = existingTransfer.get();

            boolean sameRequest = transfer.getFromAccount().getId().equals(fromId)
                    && transfer.getToAccount().getId().equals(toId)
                    && transfer.getAmountMinor().longValue() == amountMinor;

            if (!sameRequest) {
                throw new ConflictException(
                        "Idempotency-Key has already been used with a different transfer request");
            }

            return new TransferExecution(toTransferResponse(transfer), true);
        }

        // Lock both rows in deterministic UUID order. This prevents deadlocks when concurrent transfers
        // touch the same pair of accounts in opposite directions.
        UUID firstId;
        UUID secondId;

        if (fromId.compareTo(toId) < 0) {
            firstId = fromId;
            secondId = toId;
        } else {
            firstId = toId;
            secondId = fromId;
        }

        Account first = accountRepository.findByIdForUpdate(firstId)
                .orElseThrow(() -> new ResourceNotFoundException("Account not found"));
        Account second = accountRepository.findByIdForUpdate(secondId)
                .orElseThrow(() -> new ResourceNotFoundException("Account not found"));

        Account from = first.getId().equals(fromId) ? first : second;
        Account to = first.getId().equals(fromId) ? second : first;

        if (!from.getUser().getId().equals(userId) || !to.getUser().getId().equals(userId)) {
            throw new ResourceNotFoundException("Account not found");
        }

        if (!from.getCurrency().equals(to.getCurrency())) {
            throw new BadRequestException("Both accounts must use the same currency");
        }

        Long currentBalance = ledgerEntryRepository.calculateBalance(fromId, EntryDirection.CREDIT);
        long fromBalanceMinor = currentBalance == null ? 0L : currentBalance;

        if (fromBalanceMinor < amountMinor) {
            throw new InsufficientFundsException(
                    "Insufficient funds. Available balance in minor units: " + fromBalanceMinor);
        }

        AppUser user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));

        Instant now = Instant.now();

        Transfer transfer = new Transfer(
                UUID.randomUUID(),
                user,
                from,
                to,
                amountMinor,
                from.getCurrency(),
                key,
                now);

        // Flush first so the transfer exists before ledger rows that reference it.
        transferRepository.saveAndFlush(transfer);

        LedgerEntry debitEntry = new LedgerEntry(
                UUID.randomUUID(),
                from,
                transfer,
                EntryDirection.DEBIT,
                amountMinor,
                from.getCurrency(),
                now);

        LedgerEntry creditEntry = new LedgerEntry(
                UUID.randomUUID(),
                to,
                transfer,
                EntryDirection.CREDIT,
                amountMinor,
                to.getCurrency(),
                now);

        ledgerEntryRepository.saveAll(List.of(debitEntry, creditEntry));

        return new TransferExecution(toTransferResponse(transfer), false);
    }

    private void validateRequest(CreateTransferRequest request, String key) {
        if (key == null || key.isBlank()) {
            throw new BadRequestException("Idempotency-Key header is required");
        }

        if (key.length() > 255) {
            throw new BadRequestException("Idempotency-Key must be at most 255 characters");
        }

        if (request == null
                || request.fromAccountId() == null
                || request.toAccountId() == null
                || request.amountMinor() == null) {
            throw new BadRequestException("fromAccountId, toAccountId, and amountMinor are required");
        }

        if (request.amountMinor() <= 0L) {
            throw new BadRequestException("amountMinor must be positive");
        }

        if (request.fromAccountId().equals(request.toAccountId())) {
            throw new BadRequestException("fromAccountId and toAccountId must be different");
        }
    }

    private TransferResponse toTransferResponse(Transfer transfer) {
        return new TransferResponse(
                transfer.getId(),
                transfer.getFromAccount().getId(),
                transfer.getToAccount().getId(),
                transfer.getAmountMinor(),
                transfer.getCurrency(),
                transfer.getCreatedAt());
    }
}