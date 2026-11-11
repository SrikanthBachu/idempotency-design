package com.cts.transaction_processor.repository;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import org.springframework.stereotype.Repository;

import com.cts.transaction_processor.model.Transaction;

@Repository
public class TransactionRepository {

	private final ConcurrentMap<String, Transaction> transactions = new ConcurrentHashMap<>();

	public Transaction save(Transaction transaction) {
		Transaction snapshot = copyOf(Objects.requireNonNull(transaction, "transaction must not be null"));
		String transactionId = requireKey(snapshot.getTransactionId(), "transactionId");
		if (transactions.putIfAbsent(transactionId, snapshot) != null) {
			throw new IllegalStateException("Transaction already exists: " + transactionId);
		}
		return copyOf(snapshot);
	}

	public Transaction saveOrUpdate(Transaction transaction) {
		Transaction snapshot = copyOf(Objects.requireNonNull(transaction, "transaction must not be null"));
		String transactionId = requireKey(snapshot.getTransactionId(), "transactionId");
		return copyOf(transactions.compute(transactionId, (key, existing) -> snapshot));
	}

	public Transaction update(Transaction transaction) {
		Transaction snapshot = copyOf(Objects.requireNonNull(transaction, "transaction must not be null"));
		String transactionId = requireKey(snapshot.getTransactionId(), "transactionId");
		Transaction updated = transactions.computeIfPresent(transactionId, (key, existing) -> snapshot);
		if (updated == null) {
			throw new IllegalStateException("Transaction does not exist: " + transactionId);
		}
		return copyOf(updated);
	}

	public Optional<Transaction> findById(String transactionId) {
		return Optional.ofNullable(transactions.get(requireKey(transactionId, "transactionId")))
				.map(TransactionRepository::copyOf);
	}

	public List<Transaction> findAll() {
		return transactions.values().stream().map(TransactionRepository::copyOf).toList();
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
}
