package com.cts.transaction_processor.model;

/**
 * Processing state of a transaction.
 *
 * <p>Expected transitions:
 * PENDING -> RECEIVED;
 * RECEIVED -> PROCESSING or DUPLICATE;
 * PROCESSING -> PROCESSED, RETRY_PENDING, FAILED, or INVALID;
 * RETRY_PENDING -> PROCESSING or FAILED.
 * PROCESSED, DUPLICATE, FAILED, and INVALID are terminal states.</p>
 */
public enum TransactionStatus {
	PENDING,
	RECEIVED,
	PROCESSING,
	PROCESSED,
	DUPLICATE,
	FAILED,
	INVALID,
	RETRY_PENDING
}
