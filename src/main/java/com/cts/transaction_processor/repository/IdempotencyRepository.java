package com.cts.transaction_processor.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.cts.transaction_processor.model.IdempotencyRecord;

public interface IdempotencyRepository extends JpaRepository<IdempotencyRecord, String> {

	Optional<IdempotencyRecord> findByReversedTransactionId(String reversedTransactionId);
}
