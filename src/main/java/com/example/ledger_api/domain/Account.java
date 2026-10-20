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
@Table(name = "accounts")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Account {

    @Id
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private AppUser user;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(nullable = false, length = 3)
    private String currency;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    public Account(UUID id, AppUser user, String name, String currency, Instant createdAt) {
        this.id = id;
        this.user = user;
        this.name = name;
        this.currency = currency;
        this.createdAt = createdAt;
    }
}