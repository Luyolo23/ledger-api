package com.example.ledger_api.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "transfers")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Transfer {

    @Id
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private AppUser user;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "from_account_id", nullable = false)
    private Account fromAccount;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "to_account_id", nullable = false)
    private Account toAccount;

    @Column(name = "amount_minor", nullable = false)
    private Long amountMinor;

    @Column(nullable = false, length = 3)
    private String currency;

    @Column(name = "idempotency_key", nullable = false, length = 255)
    private String idempotencyKey;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    public Transfer(UUID id, AppUser user, Account fromAccount, Account toAccount, Long amountMinor, String currency, String idempotencyKey, Instant createdAt) {
        this.id = id;
        this.user = user;
        this.fromAccount = fromAccount;
        this.toAccount = toAccount;
        this.amountMinor = amountMinor;
        this.currency = currency;
        this.idempotencyKey = idempotencyKey;
        this.createdAt = createdAt;
    }
}