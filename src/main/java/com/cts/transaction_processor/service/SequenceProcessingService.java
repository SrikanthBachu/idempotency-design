package com.cts.transaction_processor.service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import org.springframework.stereotype.Service;

import com.cts.transaction_processor.model.Transaction;
import com.cts.transaction_processor.model.TransactionStatus;
import com.cts.transaction_processor.repository.TransactionRepository;

@Service
public class SequenceProcessingService {

	private final TransactionValidationService validationService;
	private final TransactionRetryService transactionExecutionService;
	private final TransactionRepository transactionRepository;

	public SequenceProcessingService(
			TransactionValidationService validationService,
			TransactionRetryService transactionExecutionService,
			TransactionRepository transactionRepository) {
		this.validationService = Objects.requireNonNull(validationService, "validationService must not be null");
		this.transactionExecutionService = Objects.requireNonNull(
				transactionExecutionService, "transactionExecutionService must not be null");
		this.transactionRepository = Objects.requireNonNull(
				transactionRepository, "transactionRepository must not be null");
	}

	public synchronized SequenceProcessingResult submit(Transaction request) {
		Transaction transaction = request == null ? null : copyOf(request);
		TransactionValidationResult validation = validationService.validate(transaction);
		if (!canBeSequenced(transaction)) {
			return result(transaction, SequenceProcessingResult.Status.UNSEQUENCABLE,
					"accountId and a positive sequenceNumber are required to place a record in the sequence",
					1, List.of(), List.of(), List.of(), validation.errors());
		}

		String accountId = transaction.getAccountId();
		Transaction stored = transactionRepository.findById(transaction.getTransactionId()).orElse(null);
		if (stored != null && !sameSequencePayload(stored, transaction)) {
			return conflict(transaction, accountId,
					"transactionId is already associated with a different payload");
		}
		List<Transaction> slot = transactionsAt(accountId, transaction.getSequenceNumber());
		if (!slot.isEmpty() && !slot.get(0).getTransactionId().equals(transaction.getTransactionId())) {
			return conflict(transaction, accountId,
					"sequenceNumber is already associated with a different transaction");
		}
		if (stored == null) {
			transaction.setStatus(TransactionStatus.RECEIVED);
			stored = transactionRepository.saveAndFlush(transaction);
		}

		long nextExpected = nextExpectedSequence(accountId);
		if (transaction.getSequenceNumber() < nextExpected || isTerminal(stored.getStatus())) {
			if (stored.getStatus() == TransactionStatus.PROCESSED
					|| stored.getStatus() == TransactionStatus.DUPLICATE) {
				IdempotencyResult duplicateResult = transactionExecutionService.process(transaction);
				if (duplicateResult.status() == IdempotencyResult.Status.CONFLICT) {
					return conflict(transaction, accountId, duplicateResult.message());
				}
			}
			return result(transaction, SequenceProcessingResult.Status.DUPLICATE,
					"sequenceNumber has already been processed", nextExpected,
					missingSequences(accountId), pendingSequences(accountId), List.of(), List.of());
		}

		List<SequenceExecution> executions = drain(accountId);
		SequenceExecution submittedExecution = executions.stream()
				.filter(execution -> transaction.getTransactionId().equals(execution.transactionId()))
				.findFirst()
				.orElse(null);
		SequenceProcessingResult.Status status;
		String message;
		if (submittedExecution == null) {
			status = SequenceProcessingResult.Status.BUFFERED;
			message = "Waiting for missing predecessor sequence(s)";
		} else {
			status = executionStatus(submittedExecution);
			message = submittedExecution.terminalInvalid()
					? "Invalid record was recorded as terminal and advanced the sequence"
					: submittedExecution.executionResult().message();
		}

		return result(transaction, status, message, nextExpectedSequence(accountId),
				missingSequences(accountId), pendingSequences(accountId), executions, validation.errors());
	}

	public List<SequenceProcessingResult> submitBatch(List<Transaction> requests) {
		Objects.requireNonNull(requests, "requests must not be null");
		return requests.stream().map(this::submit).toList();
	}

	public synchronized SequenceProcessingResult recoverPending(String accountId) {
		if (accountId == null || accountId.isBlank()) {
			throw new IllegalArgumentException("accountId must not be blank");
		}
		List<SequenceExecution> executions = drain(accountId);
		return new SequenceProcessingResult(
				accountId,
				0,
				SequenceProcessingResult.Status.RECOVERY,
				"Pending records drained where predecessors are available",
				nextExpectedSequence(accountId),
				missingSequences(accountId),
				pendingSequences(accountId),
				executions,
				List.of());
	}

	public List<Transaction> getPendingTransactions(String accountId) {
		if (accountId == null || accountId.isBlank()) {
			throw new IllegalArgumentException("accountId must not be blank");
		}
		return transactionRepository.findByAccountId(accountId).stream()
				.filter(transaction -> !isTerminal(transaction.getStatus()))
				.map(SequenceProcessingService::copyOf)
				.toList();
	}

	private List<SequenceExecution> drain(String accountId) {
		List<SequenceExecution> executions = new ArrayList<>();
		while (true) {
			long nextExpected = nextExpectedSequence(accountId);
			Transaction transaction = transactionsAt(accountId, nextExpected).stream()
					.findFirst()
					.orElse(null);
			if (transaction == null || isTerminal(transaction.getStatus())) {
				return executions;
			}

			TransactionValidationResult validation = validationService.validate(transaction);
			SequenceExecution execution;
			if (!validation.isValid()) {
				String failureReason = String.join("; ", validation.errors());
				transaction.setStatus(TransactionStatus.FAILED);
				transaction.setFailureReason(failureReason);
				transactionRepository.save(transaction);
				execution = new SequenceExecution(
						transaction.getSequenceNumber(), transaction.getTransactionId(), true, null,
						validation.errors());
			} else {
				transaction.setStatus(TransactionStatus.PROCESSING);
				transaction.setFailureReason(null);
				transactionRepository.save(transaction);
				IdempotencyResult executionResult = transactionExecutionService.process(transaction);
				updateFromResult(transaction, executionResult);
				transactionRepository.save(transaction);
				execution = new SequenceExecution(
						transaction.getSequenceNumber(), transaction.getTransactionId(), false,
						executionResult, executionResult.errors());
			}
			executions.add(execution);
			markConflictingSlotEntriesFailed(
					accountId, transaction.getSequenceNumber(), transaction.getTransactionId());
		}
	}

	private void markConflictingSlotEntriesFailed(
			String accountId, long sequenceNumber, String canonicalTransactionId) {
		for (Transaction conflicting : transactionsAt(accountId, sequenceNumber)) {
			if (!conflicting.getTransactionId().equals(canonicalTransactionId)
					&& !isTerminal(conflicting.getStatus())) {
				conflicting.setStatus(TransactionStatus.FAILED);
				conflicting.setFailureReason(
						"sequenceNumber is already associated with a different transaction");
				transactionRepository.save(conflicting);
			}
		}
	}

	private void updateFromResult(Transaction transaction, IdempotencyResult result) {
		TransactionStatus status = switch (result.status()) {
			case PROCESSED -> TransactionStatus.PROCESSED;
			case DUPLICATE_REQUEST, DUPLICATE_TRANSACTION -> TransactionStatus.DUPLICATE;
			case INVALID, FAILED, TRANSIENT_FAILURE, CONFLICT, REJECTED -> TransactionStatus.FAILED;
		};
		transaction.setStatus(status);
		transaction.setRetryCount(result.retryCount());
		transaction.setFailureReason(result.failureReason() != null
				? result.failureReason()
				: result.errors().isEmpty() ? null : String.join("; ", result.errors()));
	}

	private long nextExpectedSequence(String accountId) {
		long expected = 1;
		List<Transaction> transactions = transactionRepository.findByAccountId(accountId).stream()
				.sorted((first, second) -> {
					int sequenceOrder = Long.compare(first.getSequenceNumber(), second.getSequenceNumber());
					if (sequenceOrder != 0) {
						return sequenceOrder;
					}
					int createdOrder = first.getCreatedAt().compareTo(second.getCreatedAt());
					return createdOrder != 0
							? createdOrder
							: first.getTransactionId().compareTo(second.getTransactionId());
				})
				.toList();
		for (int index = 0; index < transactions.size();) {
			Transaction canonical = transactions.get(index);
			long sequence = canonical.getSequenceNumber();
			if (sequence < expected) {
				index++;
				while (index < transactions.size()
						&& transactions.get(index).getSequenceNumber() == sequence) {
					index++;
				}
				continue;
			}
			if (sequence != expected || !isTerminal(canonical.getStatus())) {
				return expected;
			}
			if (expected == Long.MAX_VALUE) {
				return Long.MAX_VALUE;
			}
			expected++;
			index++;
			while (index < transactions.size()
					&& transactions.get(index).getSequenceNumber() == sequence) {
				index++;
			}
		}
		return expected;
	}

	private List<SequenceGap> missingSequences(String accountId) {
		long expected = nextExpectedSequence(accountId);
		List<Long> pending = pendingSequences(accountId);
		if (pending.isEmpty()) {
			return List.of();
		}
		List<SequenceGap> gaps = new ArrayList<>();
		long firstMissing = expected;
		for (long pendingSequence : pending) {
			if (pendingSequence > firstMissing) {
				gaps.add(new SequenceGap(firstMissing, pendingSequence - 1));
			}
			if (pendingSequence == Long.MAX_VALUE) {
				return List.copyOf(gaps);
			}
			firstMissing = pendingSequence + 1;
		}
		return List.copyOf(gaps);
	}

	private List<Long> pendingSequences(String accountId) {
		long expected = nextExpectedSequence(accountId);
		List<Long> pending = new ArrayList<>();
		long previousSequence = -1;
		for (Transaction transaction : transactionRepository.findByAccountId(accountId).stream()
				.sorted((first, second) -> {
					int sequenceOrder = Long.compare(first.getSequenceNumber(), second.getSequenceNumber());
					if (sequenceOrder != 0) {
						return sequenceOrder;
					}
					int createdOrder = first.getCreatedAt().compareTo(second.getCreatedAt());
					return createdOrder != 0
							? createdOrder
							: first.getTransactionId().compareTo(second.getTransactionId());
				})
				.toList()) {
			if (transaction.getSequenceNumber() >= expected
					&& transaction.getSequenceNumber() != previousSequence
					&& !isTerminal(transaction.getStatus())) {
				pending.add(transaction.getSequenceNumber());
			}
			previousSequence = transaction.getSequenceNumber();
		}
		return List.copyOf(pending);
	}

	private List<Transaction> transactionsAt(String accountId, long sequenceNumber) {
		return transactionRepository.findByAccountIdAndSequenceNumberOrderByCreatedAtAscTransactionIdAsc(
				accountId, sequenceNumber);
	}

	private SequenceProcessingResult conflict(Transaction transaction, String accountId, String message) {
		return result(transaction, SequenceProcessingResult.Status.CONFLICT, message,
				nextExpectedSequence(accountId), missingSequences(accountId),
				pendingSequences(accountId), List.of(), List.of());
	}

	private static boolean isTerminal(TransactionStatus status) {
		return status == TransactionStatus.PROCESSED
				|| status == TransactionStatus.DUPLICATE
				|| status == TransactionStatus.FAILED
				|| status == TransactionStatus.INVALID;
	}

	private static SequenceProcessingResult.Status executionStatus(SequenceExecution execution) {
		if (execution.terminalInvalid()) {
			return SequenceProcessingResult.Status.INVALID;
		}
		return switch (execution.executionResult().status()) {
			case CONFLICT -> SequenceProcessingResult.Status.CONFLICT;
			case INVALID -> SequenceProcessingResult.Status.INVALID;
			case FAILED -> SequenceProcessingResult.Status.FAILED;
			case DUPLICATE_REQUEST, DUPLICATE_TRANSACTION -> SequenceProcessingResult.Status.DUPLICATE;
			case TRANSIENT_FAILURE -> SequenceProcessingResult.Status.FAILED;
			case REJECTED -> SequenceProcessingResult.Status.REJECTED;
			case PROCESSED -> SequenceProcessingResult.Status.PROCESSED;
		};
	}

	private static boolean canBeSequenced(Transaction transaction) {
		return transaction != null
				&& transaction.getAccountId() != null
				&& !transaction.getAccountId().isBlank()
				&& transaction.getSequenceNumber() > 0;
	}

	private static boolean sameSequencePayload(Transaction first, Transaction second) {
		return Objects.equals(first.getTransactionId(), second.getTransactionId())
				&& Objects.equals(first.getAccountId(), second.getAccountId())
				&& Objects.equals(first.getOriginalTransactionId(), second.getOriginalTransactionId())
				&& first.getTransactionType() == second.getTransactionType()
				&& sameAmount(first.getAmount(), second.getAmount())
				&& Objects.equals(first.getCurrency(), second.getCurrency())
				&& first.getSequenceNumber() == second.getSequenceNumber();
	}

	private static boolean sameAmount(BigDecimal first, BigDecimal second) {
		return first == null ? second == null : second != null && first.compareTo(second) == 0;
	}

	private static Transaction copyOf(Transaction transaction) {
		Transaction copy = new Transaction(
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
		copy.setFailureReason(transaction.getFailureReason());
		return copy;
	}

	private static SequenceProcessingResult result(
			Transaction transaction,
			SequenceProcessingResult.Status status,
			String message,
			long nextExpectedSequence,
			List<SequenceGap> missingSequences,
			List<Long> pendingSequences,
			List<SequenceExecution> executions,
			List<String> errors) {
		return new SequenceProcessingResult(
				transaction == null ? null : transaction.getAccountId(),
				transaction == null ? 0 : transaction.getSequenceNumber(),
				status,
				message,
				nextExpectedSequence,
				missingSequences,
				pendingSequences,
				executions,
				errors);
	}
}
