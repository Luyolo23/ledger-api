package com.example.ledger_api.repository;

import com.example.ledger_api.domain.EntryDirection;
import com.example.ledger_api.domain.LedgerEntry;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.UUID;

public interface LedgerEntryRepository extends JpaRepository<LedgerEntry, UUID>, JpaSpecificationExecutor<LedgerEntry> {

    @Query("""
            select sum(case when e.direction = :credit then e.amountMinor else -e.amountMinor end)
            from LedgerEntry e
            where e.account.id = :accountId
            """)
    Long calculateBalance(@Param("accountId") UUID accountId, @Param("credit") EntryDirection creditDirection);
}
