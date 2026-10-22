package com.example.ledger_api.service;

import com.example.ledger_api.domain.Account;
import com.example.ledger_api.domain.AppUser;
import com.example.ledger_api.domain.EntryDirection;
import com.example.ledger_api.domain.LedgerEntry;
import com.example.ledger_api.dto.account.AccountResponse;
import com.example.ledger_api.dto.account.CreateAccountRequest;
import com.example.ledger_api.dto.ledger.LedgerEntryResponse;
import com.example.ledger_api.exception.BadRequestException;
import com.example.ledger_api.exception.ResourceNotFoundException;
import com.example.ledger_api.repository.AccountRepository;
import com.example.ledger_api.repository.LedgerEntryRepository;
import com.example.ledger_api.repository.UserRepository;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

@Service
public class AccountService {

    private final AccountRepository accountRepository;
    private final LedgerEntryRepository ledgerEntryRepository;
    private final UserRepository userRepository;

    public AccountService(
            AccountRepository accountRepository,
            LedgerEntryRepository ledgerEntryRepository,
            UserRepository userRepository
    ) {
        this.accountRepository = accountRepository;
        this.ledgerEntryRepository = ledgerEntryRepository;
        this.userRepository = userRepository;
    }

    @Transactional
    public AccountResponse create(UUID userId, CreateAccountRequest request) {
        AppUser user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));

        String currency = request.currency() == null
                ? "ZAR"
                : request.currency().toUpperCase(Locale.ROOT);

        if (!currency.matches("[A-Z]{3}")) {
            throw new BadRequestException("currency must be a 3-letter ISO code");
        }

        Account account = new Account(
                UUID.randomUUID(),
                user,
                request.name().trim(),
                currency,
                Instant.now());

        accountRepository.save(account);

        return toAccountResponse(account);
    }

    @Transactional(readOnly = true)
    public List<AccountResponse> list(UUID userId) {
        return accountRepository.findAllByUser_IdOrderByCreatedAtDesc(userId)
                .stream()
                .map(this::toAccountResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public AccountResponse get(UUID userId, UUID accountId) {
        Account account = accountRepository.findByIdAndUser_Id(accountId, userId)
                .orElseThrow(() -> new ResourceNotFoundException("Account not found"));

        return toAccountResponse(account);
    }

    @Transactional(readOnly = true)
    public Page<LedgerEntryResponse> history(
            UUID userId,
            UUID accountId,
            Instant from,
            Instant to,
            int page,
            int size
    ) {
        accountRepository.findByIdAndUser_Id(accountId, userId)
                .orElseThrow(() -> new ResourceNotFoundException("Account not found"));

        if (from != null && to != null && from.isAfter(to)) {
            throw new BadRequestException("'from' must be before or equal to 'to'");
        }

        Pageable pageable = PageRequest.of(
                page,
                size,
                Sort.by(
                        Sort.Order.desc("createdAt"),
                        Sort.Order.desc("id")));

        Specification<LedgerEntry> specification = (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            predicates.add(cb.equal(root.get("account").get("id"), accountId));

            if (from != null) {
                predicates.add(cb.greaterThanOrEqualTo(root.get("createdAt"), from));
            }
            if (to != null) {
                predicates.add(cb.lessThanOrEqualTo(root.get("createdAt"), to));
            }

            return cb.and(predicates.toArray(Predicate[]::new));
        };

        return ledgerEntryRepository.findAll(specification, pageable)
                .map(this::toLedgerEntryResponse);
    }

    private AccountResponse toAccountResponse(Account account) {
        return new AccountResponse(
                account.getId(),
                account.getName(),
                account.getCurrency(),
                balanceMinor(account.getId()),
                account.getCreatedAt());
    }

    private LedgerEntryResponse toLedgerEntryResponse(LedgerEntry entry) {
        return new LedgerEntryResponse(
                entry.getId(),
                entry.getTransfer().getId(),
                entry.getAccount().getId(),
                entry.getDirection().name(),
                entry.getAmountMinor(),
                entry.getCurrency(),
                entry.getCreatedAt());
    }

    private long balanceMinor(UUID accountId) {
        Long balance = ledgerEntryRepository.calculateBalance(accountId, EntryDirection.CREDIT);
        return balance == null ? 0L : balance;
    }
}