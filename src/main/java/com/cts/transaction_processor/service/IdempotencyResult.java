package com.cts.transaction_processor.service;

import java.math.BigDecimal;
import java.util.List;

import com.cts.transaction_processor.model.TransactionStatus;

public record IdempotencyResult(
		Status status,
		String message,
		BigDecimal balance,
		List<String> errors,
		TransactionStatus transactionStatus,
		int retryCount,
		String failureReason) {

	public IdempotencyResult {
		errors = List.copyOf(errors);
	}

	public enum Status {
		PROCESSED,
		DUPLICATE_REQUEST,
		DUPLICATE_TRANSACTION,
		CONFLICT,
		INVALID,
		REJECTED,
		TRANSIENT_FAILURE,
		FAILED
	}
}
