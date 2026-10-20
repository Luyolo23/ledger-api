package com.example.ledger_api.repository;

import com.example.ledger_api.domain.Transfer;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface TransferRepository extends JpaRepository<Transfer, UUID> {

    Optional<Transfer> findByUser_IdAndIdempotencyKey(UUID userId, String idempotencyKey);
}
