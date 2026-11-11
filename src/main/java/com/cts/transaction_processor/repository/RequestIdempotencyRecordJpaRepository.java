package com.cts.transaction_processor.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import com.cts.transaction_processor.model.RequestIdempotencyRecord;

public interface RequestIdempotencyRecordJpaRepository
		extends JpaRepository<RequestIdempotencyRecord, String> {
}
