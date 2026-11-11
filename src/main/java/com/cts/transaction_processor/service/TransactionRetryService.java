package com.cts.transaction_processor.service;

import java.util.Objects;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.cts.transaction_processor.model.Transaction;
import com.cts.transaction_processor.model.TransactionStatus;

@Service
public class TransactionRetryService {

	public static final int MAX_RETRIES = 3;

	private static final Logger LOGGER = LoggerFactory.getLogger(TransactionRetryService.class);

	private final IdempotencyService transactionExecutor;

	public TransactionRetryService(IdempotencyService transactionExecutor) {
		this.transactionExecutor = Objects.requireNonNull(transactionExecutor, "transactionExecutor must not be null");
	}

	public synchronized IdempotencyResult process(Transaction transaction) {
		if (transaction == null) {
			return transactionExecutor.process(null);
		}

		int retryCount = Math.max(0, transaction.getRetryCount());
		while (true) {
			transaction.setRetryCount(retryCount);
			transaction.setStatus(TransactionStatus.PROCESSING);

			IdempotencyResult result = transactionExecutor.process(transaction);
			if (result.status() != IdempotencyResult.Status.TRANSIENT_FAILURE) {
				if (result.status() == IdempotencyResult.Status.PROCESSED) {
					transaction.setStatus(TransactionStatus.PROCESSED);
					transactionExecutor.recordRetryState(transaction, TransactionStatus.PROCESSED, null);
				} else if (result.status() == IdempotencyResult.Status.DUPLICATE_REQUEST
						|| result.status() == IdempotencyResult.Status.DUPLICATE_TRANSACTION) {
					transaction.setStatus(TransactionStatus.DUPLICATE);
				} else if (result.status() == IdempotencyResult.Status.INVALID) {
					transaction.setStatus(TransactionStatus.INVALID);
					String failureReason = String.join("; ", result.errors());
					transactionExecutor.recordRetryState(transaction, TransactionStatus.INVALID, failureReason);
					return withAttemptState(result, transaction.getStatus(), retryCount, failureReason);
				} else if (result.status() == IdempotencyResult.Status.FAILED
						|| result.status() == IdempotencyResult.Status.CONFLICT
						|| result.status() == IdempotencyResult.Status.REJECTED) {
					transaction.setStatus(TransactionStatus.FAILED);
					String failureReason = result.failureReason() != null
							? result.failureReason()
							: result.errors().isEmpty() ? result.message() : String.join("; ", result.errors());
					transactionExecutor.recordRetryState(transaction, TransactionStatus.FAILED, failureReason);
					return withAttemptState(result, transaction.getStatus(), retryCount, failureReason);
				}
				return withAttemptState(result, transaction.getStatus(), retryCount, null);
			}

			String failureReason = result.failureReason();
			if (retryCount >= MAX_RETRIES) {
				transaction.setStatus(TransactionStatus.FAILED);
				transactionExecutor.recordRetryState(transaction, TransactionStatus.FAILED, failureReason);
				LOGGER.error("Transaction processing failed after retries: transactionId={}, retryCount={}, reason={}",
						transaction.getTransactionId(), retryCount, failureReason);
				return new IdempotencyResult(
						IdempotencyResult.Status.FAILED,
						"Transaction failed after exhausting retries",
						null,
						result.errors(),
						TransactionStatus.FAILED,
						retryCount,
						failureReason);
			}

			retryCount++;
			transaction.setRetryCount(retryCount);
			transaction.setStatus(TransactionStatus.RETRY_PENDING);
			transactionExecutor.recordRetryState(transaction, TransactionStatus.RETRY_PENDING, failureReason);
			LOGGER.warn("Transient transaction failure: transactionId={}, retryCount={}, reason={}",
					transaction.getTransactionId(), retryCount, failureReason);
		}
	}

	private static IdempotencyResult withAttemptState(
			IdempotencyResult result, TransactionStatus status, int retryCount, String failureReason) {
		return new IdempotencyResult(
				result.status(),
				result.message(),
				result.balance(),
				result.errors(),
				status,
				retryCount,
				failureReason);
	}
}
