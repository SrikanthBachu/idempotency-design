package com.cts.transaction_processor.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.cts.transaction_processor.model.Transaction;
import com.cts.transaction_processor.repository.TransactionResultRepository;
import com.cts.transaction_processor.repository.TransactionResultRepository.SubmissionResult;

@Service
public class TransactionProcessor {

	private static final Logger LOGGER = LoggerFactory.getLogger(TransactionProcessor.class);

	private final SequenceProcessingService sequenceProcessingService;
	private final TransactionResultRepository resultRepository;

	public TransactionProcessor(
			SequenceProcessingService sequenceProcessingService,
			TransactionResultRepository resultRepository) {
		this.sequenceProcessingService = Objects.requireNonNull(
				sequenceProcessingService, "sequenceProcessingService must not be null");
		this.resultRepository = Objects.requireNonNull(resultRepository, "resultRepository must not be null");
	}

	public SequenceProcessingResult process(Transaction request) {
		Transaction snapshot = request == null ? null : copyOf(request);
		SequenceProcessingResult result;
		try {
			result = sequenceProcessingService.submit(snapshot);
		} catch (RuntimeException exception) {
			String message = failureMessage(exception);
			LOGGER.error("Transaction processing failed; record remains eligible for recovery: "
					+ "transactionId={}, requestId={}, sequenceNumber={}, reason={}",
					snapshot == null ? null : snapshot.getTransactionId(),
					snapshot == null ? null : snapshot.getRequestId(),
					snapshot == null ? null : snapshot.getSequenceNumber(),
					message,
					exception);
			result = failedResult(snapshot, "Processing failed; record remains pending for recovery", message);
		}

		try {
			SubmissionResult persisted = resultRepository.save(snapshot, result);
			logResult(persisted, result);
			return result;
		} catch (RuntimeException exception) {
			String message = failureMessage(exception);
			LOGGER.error("Could not persist transaction result: transactionId={}, requestId={}, reason={}",
					snapshot == null ? null : snapshot.getTransactionId(),
					snapshot == null ? null : snapshot.getRequestId(),
					message,
					exception);
			return failedResult(snapshot, "Transaction outcome could not be persisted", message);
		}
	}

	public List<SequenceProcessingResult> processBatch(List<Transaction> requests) {
		Objects.requireNonNull(requests, "requests must not be null");
		List<SequenceProcessingResult> results = new ArrayList<>(requests.size());
		for (Transaction request : requests) {
			results.add(process(request));
		}
		return List.copyOf(results);
	}

	public SequenceProcessingResult recoverPending(String accountId) {
		SequenceProcessingResult result = sequenceProcessingService.recoverPending(accountId);
		SubmissionResult persisted = resultRepository.save(null, result);
		logResult(persisted, result);
		return result;
	}

	public Optional<SubmissionResult> findResultByRequestId(String requestId) {
		return resultRepository.findByRequestId(requestId);
	}

	public Optional<SequenceExecution> findExecution(String accountId, long sequenceNumber) {
		return resultRepository.findExecution(accountId, sequenceNumber);
	}

	private static SequenceProcessingResult failedResult(
			Transaction transaction, String message, String reason) {
		long sequenceNumber = transaction == null ? 0 : transaction.getSequenceNumber();
		String accountId = transaction == null ? null : transaction.getAccountId();
		return new SequenceProcessingResult(
				accountId,
				sequenceNumber,
				SequenceProcessingResult.Status.FAILED,
				message,
				0,
				List.of(),
				sequenceNumber > 0 ? List.of(sequenceNumber) : List.of(),
				List.of(),
				List.of(reason));
	}

	private static String failureMessage(RuntimeException exception) {
		String message = exception.getMessage();
		return exception.getClass().getSimpleName()
				+ (message == null || message.isBlank() ? "" : ": " + message);
	}

	private static void logResult(SubmissionResult persisted, SequenceProcessingResult result) {
		LOGGER.info("Transaction submission completed: resultId={}, transactionId={}, requestId={}, "
						+ "sequenceNumber={}, status={}, executions={}, pendingSequences={}",
				persisted.id(),
				persisted.transactionId(),
				persisted.requestId(),
				persisted.sequenceNumber(),
				result.status(),
				result.executions().size(),
				result.pendingSequences());
	}

	private static Transaction copyOf(Transaction transaction) {
		Transaction snapshot = new Transaction(
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
		return snapshot;
	}
}
