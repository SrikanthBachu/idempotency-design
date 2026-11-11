package com.cts.transaction_processor.repository;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import org.springframework.stereotype.Repository;

import com.cts.transaction_processor.model.Transaction;
import com.cts.transaction_processor.service.SequenceExecution;
import com.cts.transaction_processor.service.SequenceProcessingResult;

@Repository
public class TransactionResultRepository {

	private final ConcurrentMap<String, SubmissionResult> resultsByRequestId = new ConcurrentHashMap<>();
	private final ConcurrentMap<String, SequenceExecution> executionsBySequence = new ConcurrentHashMap<>();
	private final ConcurrentMap<String, SubmissionResult> resultsById = new ConcurrentHashMap<>();

	public SubmissionResult save(Transaction transaction, SequenceProcessingResult result) {
		Objects.requireNonNull(result, "result must not be null");
		String id = UUID.randomUUID().toString();
		String requestId = transaction == null ? null : transaction.getRequestId();
		SubmissionResult stored = new SubmissionResult(
				id,
				requestId,
				transaction == null ? null : transaction.getTransactionId(),
				transaction == null ? result.accountId() : transaction.getAccountId(),
				transaction == null ? result.submittedSequence() : transaction.getSequenceNumber(),
				result,
				Instant.now());
		resultsById.put(id, stored);
		if (requestId != null && !requestId.isBlank()) {
			resultsByRequestId.put(requestId, stored);
		}
		for (SequenceExecution execution : result.executions()) {
			if (result.accountId() != null) {
				executionsBySequence.put(sequenceKey(result.accountId(), execution.sequenceNumber()), execution);
			}
		}
		return stored;
	}

	public Optional<SubmissionResult> findByRequestId(String requestId) {
		return Optional.ofNullable(resultsByRequestId.get(requireKey(requestId, "requestId")));
	}

	public Optional<SubmissionResult> findById(String resultId) {
		return Optional.ofNullable(resultsById.get(requireKey(resultId, "resultId")));
	}

	public Optional<SequenceExecution> findExecution(String accountId, long sequenceNumber) {
		return Optional.ofNullable(executionsBySequence.get(sequenceKey(accountId, sequenceNumber)));
	}

	public List<SubmissionResult> findAll() {
		return List.copyOf(resultsById.values());
	}

	private static String sequenceKey(String accountId, long sequenceNumber) {
		return requireKey(accountId, "accountId") + ":" + sequenceNumber;
	}

	private static String requireKey(String key, String name) {
		Objects.requireNonNull(key, name + " must not be null");
		if (key.isBlank()) {
			throw new IllegalArgumentException(name + " must not be blank");
		}
		return key;
	}

	public record SubmissionResult(
			String id,
			String requestId,
			String transactionId,
			String accountId,
			long sequenceNumber,
			SequenceProcessingResult result,
			Instant recordedAt) {
	}
}
