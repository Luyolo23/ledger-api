package com.example.ledger_api.repository;

import com.example.ledger_api.domain.Account;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AccountRepository extends JpaRepository<Account, UUID> {
    List<Account> findAllByUser_IdOrderByCreatedAtDesc(UUID userId);

    Optional<Account> findByIdAndUser_id(UUID id, UUID userId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from Account a where a.id =:id")
    Optional<Account> findByIdForUpdate(@Param("id") UUID id);

}
