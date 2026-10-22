package com.example.ledger_api.service;

import com.example.ledger_api.domain.Account;
import com.example.ledger_api.domain.AppUser;
import com.example.ledger_api.domain.EntryDirection;
import com.example.ledger_api.domain.Transfer;
import com.example.ledger_api.dto.transfer.CreateTransferRequest;
import com.example.ledger_api.exception.ConflictException;
import com.example.ledger_api.exception.InsufficientFundsException;
import com.example.ledger_api.repository.AccountRepository;
import com.example.ledger_api.repository.IdempotencyLockRepository;
import com.example.ledger_api.repository.LedgerEntryRepository;
import com.example.ledger_api.repository.TransferRepository;
import com.example.ledger_api.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TransferServiceTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private AccountRepository accountRepository;

    @Mock
    private TransferRepository transferRepository;

    @Mock
    private LedgerEntryRepository ledgerEntryRepository;

    @Mock
    private IdempotencyLockRepository idempotencyLockRepository;

    @InjectMocks
    private TransferService transferService;

    private final UUID userId = UUID.fromString("00000000-0000-0000-0000-000000000100");
    private final UUID fromId = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private final UUID toId = UUID.fromString("00000000-0000-0000-0000-000000000002");

    private AppUser user;
    private Account from;
    private Account to;

    @BeforeEach
    void setUp() {
        user = new AppUser(userId, "test@example.com", "hash", Instant.parse("2025-01-01T00:00:00Z"));
        from = new Account(fromId, user, "From", "ZAR", Instant.parse("2025-01-01T00:00:00Z"));
        to = new Account(toId, user, "To", "ZAR", Instant.parse("2025-01-01T00:00:00Z"));
    }

    @Test
    void replaysExistingTransferWithoutLockingAccounts() {
        UUID transferId = UUID.randomUUID();
        Transfer existing = new Transfer(
                transferId,
                user,
                from,
                to,
                100L,
                "ZAR",
                "key",
                Instant.now());

        when(transferRepository.findByUser_IdAndIdempotencyKey(userId, "key"))
                .thenReturn(Optional.of(existing));

        TransferExecution result = transferService.transfer(
                userId,
                "key",
                new CreateTransferRequest(fromId, toId, 100L));

        assertTrue(result.replayed());
        assertEquals(transferId, result.transfer().transferId());
        verify(accountRepository, never()).findByIdForUpdate(any());
        verify(transferRepository, never()).saveAndFlush(any());
    }

    @Test
    void rejectsInsufficientFundsBeforeCreatingTransfer() {
        when(transferRepository.findByUser_IdAndIdempotencyKey(userId, "funds"))
                .thenReturn(Optional.empty());
        when(accountRepository.findByIdForUpdate(fromId)).thenReturn(Optional.of(from));
        when(accountRepository.findByIdForUpdate(toId)).thenReturn(Optional.of(to));
        when(ledgerEntryRepository.calculateBalance(fromId, EntryDirection.CREDIT))
                .thenReturn(50L);

        assertThrows(
                InsufficientFundsException.class,
                () -> transferService.transfer(
                        userId,
                        "funds",
                        new CreateTransferRequest(fromId, toId, 100L)));

        verify(transferRepository, never()).saveAndFlush(any());
        verify(ledgerEntryRepository, never()).saveAll(any());
    }

    @Test
    void rejectsIdempotencyKeyReuseWithDifferentPayload() {
        Transfer existing = new Transfer(
                UUID.randomUUID(),
                user,
                from,
                to,
                100L,
                "ZAR",
                "key",
                Instant.now());

        when(transferRepository.findByUser_IdAndIdempotencyKey(userId, "key"))
                .thenReturn(Optional.of(existing));

        ConflictException ex = assertThrows(
                ConflictException.class,
                () -> transferService.transfer(
                        userId,
                        "key",
                        new CreateTransferRequest(fromId, toId, 200L)));

        assertEquals(
                "Idempotency-Key has already been used with a different transfer request",
                ex.getMessage());

        assertFalse(ex.getMessage().isBlank());
    }
}