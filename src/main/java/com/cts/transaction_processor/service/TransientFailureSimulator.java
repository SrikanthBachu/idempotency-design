package com.cts.transaction_processor.service;

import com.cts.transaction_processor.model.Transaction;

@FunctionalInterface
public interface TransientFailureSimulator {

	/**
	 * Returns a failure reason for this attempt, or {@code null} when it should proceed.
	 */
	String failureReason(Transaction transaction, int retryCount);
}
