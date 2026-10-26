package com.example.ledger_api;


import com.example.ledger_api.domain.Account;
import com.example.ledger_api.domain.AppUser;
import com.example.ledger_api.domain.EntryDirection;
import com.example.ledger_api.domain.LedgerEntry;
import com.example.ledger_api.domain.Transfer;
import com.example.ledger_api.dto.transfer.CreateTransferRequest;
import com.example.ledger_api.exception.ConflictException;
import com.example.ledger_api.exception.InsufficientFundsException;
import com.example.ledger_api.repository.AccountRepository;
import com.example.ledger_api.repository.LedgerEntryRepository;
import com.example.ledger_api.repository.TransferRepository;
import com.example.ledger_api.repository.UserRepository;
import com.example.ledger_api.service.TransferExecution;
import com.example.ledger_api.service.TransferService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Testcontainers
@SpringBootTest
class LedgerIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.datasource.hikari.maximum-pool-size", () -> "20");
        registry.add("app.jwt.secret", () -> "integration-test-secret-that-is-long-enough-1234567890");
    }

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private TransferRepository transferRepository;

    @Autowired
    private LedgerEntryRepository ledgerEntryRepository;

    @Autowired
    private TransferService transferService;

    @BeforeEach
    void cleanDatabase() {
        jdbcTemplate.execute("TRUNCATE TABLE ledger_entries, transfers, accounts, users CASCADE");
    }

    @Test
    void concurrentTransfersNeverOverdrawAndPreserveTotalMoney() throws Exception {
        TestData data = createFundedAccounts(1000L);

        int attempts = 20;
        long transferAmount = 100L;
        ExecutorService executor = Executors.newFixedThreadPool(attempts);

        CountDownLatch ready = new CountDownLatch(attempts);
        CountDownLatch start = new CountDownLatch(1);

        List<Future<Boolean>> futures = new ArrayList<>();

        try {
            for (int i = 0; i < attempts; i++) {
                final int requestNumber = i;

                futures.add(executor.submit(() -> {
                    ready.countDown();

                    try {
                        start.await(10, TimeUnit.SECONDS);
                    } catch (InterruptedException ex) {
                        Thread.currentThread().interrupt();
                        return false;
                    }

                    try {
                        transferService.transfer(
                                data.user().getId(),
                                "concurrent-" + requestNumber,
                                new CreateTransferRequest(
                                        data.from().getId(),
                                        data.to().getId(),
                                        transferAmount));
                        return true;
                    } catch (InsufficientFundsException ex) {
                        return false;
                    }
                }));
            }

            assertTrue(ready.await(10, TimeUnit.SECONDS));
            start.countDown();

            int successfulTransfers = 0;

            for (Future<Boolean> future : futures) {
                if (future.get(30, TimeUnit.SECONDS)) {
                    successfulTransfers++;
                }
            }

            long fromBalance = balanceMinor(data.from().getId());
            long toBalance = balanceMinor(data.to().getId());

            assertEquals(10, successfulTransfers);
            assertTrue(fromBalance >= 0L);
            assertEquals(0L, fromBalance);
            assertEquals(1000L, toBalance);
            assertEquals(1000L, fromBalance + toBalance);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void sameIdempotencyKeyDoesNotMoveMoneyTwiceAndReturnsOriginalResult() {
        TestData data = createFundedAccounts(500L);

        CreateTransferRequest request = new CreateTransferRequest(
                data.from().getId(),
                data.to().getId(),
                100L);

        TransferExecution first = transferService.transfer(
                data.user().getId(),
                "idempotency-test-key",
                request);

        TransferExecution second = transferService.transfer(
                data.user().getId(),
                "idempotency-test-key",
                request);

        assertFalse(first.replayed());
        assertTrue(second.replayed());
        assertEquals(first.transfer().transferId(), second.transfer().transferId());

        assertEquals(400L, balanceMinor(data.from().getId()));
        assertEquals(100L, balanceMinor(data.to().getId()));
        assertEquals(2L, transferRepository.count()); // 1 demo seed + 1 actual transfer

        assertThrows(
                ConflictException.class,
                () -> transferService.transfer(
                        data.user().getId(),
                        "idempotency-test-key",
                        new CreateTransferRequest(
                                data.from().getId(),
                                data.to().getId(),
                                200L)));

        assertEquals(400L, balanceMinor(data.from().getId()));
        assertEquals(100L, balanceMinor(data.to().getId()));
        assertEquals(2L, transferRepository.count());
    }

    private TestData createFundedAccounts(long initialFromBalanceMinor) {
        return new TransactionTemplate(transactionManager).execute(status -> {
            AppUser user = new AppUser(
                    UUID.randomUUID(),
                    "user-" + UUID.randomUUID() + "@example.com",
                    "hash",
                    Instant.now());

            userRepository.save(user);

            Account from = new Account(
                    UUID.randomUUID(),
                    user,
                    "From",
                    "ZAR",
                    Instant.now());

            Account to = new Account(
                    UUID.randomUUID(),
                    user,
                    "To",
                    "ZAR",
                    Instant.now());

            accountRepository.saveAll(List.of(from, to));

            seedOpeningBalance(from, initialFromBalanceMinor);

            return new TestData(user, from, to);
        });
    }

    private void seedOpeningBalance(Account targetAccount, long amountMinor) {
        if (amountMinor <= 0L) {
            return;
        }

        AppUser systemUser = new AppUser(
                UUID.randomUUID(),
                "system-" + UUID.randomUUID() + "@test.local",
                "!disabled-account!",
                Instant.now());

        userRepository.save(systemUser);

        Account systemAccount = new Account(
                UUID.randomUUID(),
                systemUser,
                "System Equity",
                targetAccount.getCurrency(),
                Instant.now());

        accountRepository.save(systemAccount);

        Instant now = Instant.now();

        Transfer transfer = new Transfer(
                UUID.randomUUID(),
                systemUser,
                systemAccount,
                targetAccount,
                amountMinor,
                targetAccount.getCurrency(),
                "seed-" + UUID.randomUUID(),
                now);

        transferRepository.saveAndFlush(transfer);

        LedgerEntry debitEntry = new LedgerEntry(
                UUID.randomUUID(),
                systemAccount,
                transfer,
                EntryDirection.DEBIT,
                amountMinor,
                targetAccount.getCurrency(),
                now);

        LedgerEntry creditEntry = new LedgerEntry(
                UUID.randomUUID(),
                targetAccount,
                transfer,
                EntryDirection.CREDIT,
                amountMinor,
                targetAccount.getCurrency(),
                now);

        ledgerEntryRepository.saveAll(List.of(debitEntry, creditEntry));
    }

    private long balanceMinor(UUID accountId) {
        Long balance = ledgerEntryRepository.calculateBalance(accountId, EntryDirection.CREDIT);
        return balance == null ? 0L : balance;
    }

    private record TestData(AppUser user, Account from, Account to) {
    }
}