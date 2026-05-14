package com.asm.erpadapter.repository;

import com.asm.erpadapter.entity.IdempotentTransaction;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface IdempotentTransactionRepository extends JpaRepository<IdempotentTransaction, String> {
}
