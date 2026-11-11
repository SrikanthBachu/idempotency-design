package com.cts.transaction_processor.repository;

import java.math.BigDecimal;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import org.springframework.stereotype.Repository;

import com.cts.transaction_processor.model.Transaction;
import com.cts.transaction_processor.model.TransactionStatus;

@Repository
public class IdempotencyRepository {

	private final ConcurrentMap<String, ProcessedTransaction> transactions = new ConcurrentHashMap<>();
	private final ConcurrentMap<String, RequestRecord> requests = new ConcurrentHashMap<>();
	private final ConcurrentMap<String, RetryState> retryStates = new ConcurrentHashMap<>();
	private final Set<String> reversedTransactionIds = ConcurrentHashMap.newKeySet();

	public Optional<ProcessedTransaction> findTransaction(String transactionId) {
		return Optional.ofNullable(transactions.get(requireKey(transactionId, "transactionId")))
				.map(IdempotencyRepository::copyOf);
	}

	public Optional<RequestRecord> findRequest(String requestId) {
		return Optional.ofNullable(requests.get(requireKey(requestId, "requestId")))
				.map(IdempotencyRepository::copyOf);
	}

	public Optional<RetryState> findRetryState(String transactionId) {
		return Optional.ofNullable(retryStates.get(requireKey(transactionId, "transactionId")))
				.map(IdempotencyRepository::copyOf);
	}

	public void recordRetryState(Transaction transaction, TransactionStatus status, String failureReason) {
		Transaction snapshot = copyOf(Objects.requireNonNull(transaction, "transaction must not be null"));
		String transactionId = requireKey(snapshot.getTransactionId(), "transactionId");
		TransactionStatus retryStatus = Objects.requireNonNull(status, "status must not be null");
		snapshot.setStatus(retryStatus);
		retryStates.put(transactionId, new RetryState(
				snapshot, retryStatus, snapshot.getRetryCount(), failureReason));
	}

	public synchronized void recordProcessed(Transaction transaction, BigDecimal balanceDelta,
			BigDecimal balanceAfter, String requestId, String reversedTransactionId) {
		Transaction snapshot = copyOf(Objects.requireNonNull(transaction, "transaction must not be null"));
		String transactionId = requireKey(snapshot.getTransactionId(), "transactionId");
		String requestKey = requireKey(requestId, "requestId");
		String reversedId = reversedTransactionId == null
				? null
				: requireKey(reversedTransactionId, "reversedTransactionId");
		if (transactions.containsKey(transactionId)) {
			throw new IllegalStateException("Transaction already recorded: " + transactionId);
		}
		if (requests.containsKey(requestKey)) {
			throw new IllegalStateException("Request already recorded: " + requestKey);
		}
		if (reversedId != null && reversedTransactionIds.contains(reversedId)) {
			throw new IllegalStateException("Transaction already reversed: " + reversedId);
		}
		ProcessedTransaction record = new ProcessedTransaction(snapshot,
				Objects.requireNonNull(balanceDelta, "balanceDelta must not be null"),
				Objects.requireNonNull(balanceAfter, "balanceAfter must not be null"));
		transactions.put(transactionId, record);
		requests.put(requestKey, new RequestRecord(snapshot));
		if (reversedId != null) {
			reversedTransactionIds.add(reversedId);
		}
	}

	public synchronized void recordRequest(String requestId, Transaction transaction) {
		String key = requireKey(requestId, "requestId");
		Transaction snapshot = copyOf(Objects.requireNonNull(transaction, "transaction must not be null"));
		if (!key.equals(requireKey(snapshot.getRequestId(), "transaction.requestId"))) {
			throw new IllegalArgumentException("requestId does not match transaction.requestId");
		}
		if (requests.putIfAbsent(key, new RequestRecord(snapshot)) != null) {
			throw new IllegalStateException("Request already recorded: " + key);
		}
	}

	public synchronized boolean markReversed(String transactionId) {
		return reversedTransactionIds.add(requireKey(transactionId, "transactionId"));
	}

	public boolean isReversed(String transactionId) {
		return reversedTransactionIds.contains(requireKey(transactionId, "transactionId"));
	}

	private static ProcessedTransaction copyOf(ProcessedTransaction record) {
		return new ProcessedTransaction(copyOf(record.transaction()), record.balanceDelta(), record.balanceAfter());
	}

	private static RequestRecord copyOf(RequestRecord record) {
		return new RequestRecord(copyOf(record.transaction()));
	}

	private static RetryState copyOf(RetryState state) {
		return new RetryState(copyOf(state.transaction()), state.status(), state.retryCount(), state.failureReason());
	}

	private static Transaction copyOf(Transaction transaction) {
		return new Transaction(
				transaction.getTransactionId(),
				transaction.getRequestId(),
				transaction.getAccountId(),
				transaction.getOriginalTransactionId(),
				transaction.getTransactionType(),
				transaction.getAmount(),
				transaction.getCurrency(),
				transaction.getSequenceNumber(),
				transaction.getStatus(),
				transaction.getRetryCount(),
				transaction.getCreatedAt(),
				transaction.getUpdatedAt());
	}

	private static String requireKey(String key, String name) {
		Objects.requireNonNull(key, name + " must not be null");
		if (key.isBlank()) {
			throw new IllegalArgumentException(name + " must not be blank");
		}
		return key;
	}

	public record ProcessedTransaction(Transaction transaction, BigDecimal balanceDelta, BigDecimal balanceAfter) {
	}

	public record RequestRecord(Transaction transaction) {
	}

	public record RetryState(Transaction transaction, TransactionStatus status, int retryCount, String failureReason) {
	}
}
