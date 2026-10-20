package com.example.ledger_api.repository;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.stereotype.Repository;

import java.util.UUID;

@Repository
public class IdempotencyLockRepository {

    @PersistenceContext
    private EntityManager entityManager;

    /**
     * Serializes concurrent requests that use the same user-scoped idempotency key.
     *
     * PostgreSQL transaction-scoped advisory locks are released automatically at commit/rollback.
     * This closes the race where two requests both miss the initial "existing transfer" lookup
     * and then one hits the unique constraint at commit time.
     */
    public void lock(UUID userId, String idempotencyKey) {
        String lockKey = userId + ":" + idempotencyKey;

        entityManager.createNativeQuery(
                        "select pg_advisory_xact_lock(hashtextextended(:lockKey, 0))")
                .setParameter("lockKey", lockKey)
                .getResultList();
    }
}