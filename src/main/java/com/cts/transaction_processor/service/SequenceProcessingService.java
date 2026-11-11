package com.cts.transaction_processor.service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import org.springframework.stereotype.Service;

import com.cts.transaction_processor.model.Transaction;

@Service
public class SequenceProcessingService {

	private final TransactionValidationService validationService;
	private final TransactionRetryService transactionExecutionService;
	private final ConcurrentMap<String, AccountSequence> accountSequences = new ConcurrentHashMap<>();

	public SequenceProcessingService(TransactionValidationService validationService,
			TransactionRetryService transactionExecutionService) {
		this.validationService = Objects.requireNonNull(validationService, "validationService must not be null");
		this.transactionExecutionService = Objects.requireNonNull(
				transactionExecutionService, "transactionExecutionService must not be null");
	}

	public SequenceProcessingResult submit(Transaction request) {
		Transaction transaction = request == null ? null : copyOf(request);
		TransactionValidationResult validation = validationService.validate(transaction);
		if (!canBeSequenced(transaction)) {
			return result(transaction, SequenceProcessingResult.Status.UNSEQUENCABLE,
					"accountId and a positive sequenceNumber are required to place a record in the sequence",
					1, List.of(), List.of(), List.of(), validation.errors());
		}

		String accountId = transaction.getAccountId();
		AccountSequence accountSequence = accountSequences.computeIfAbsent(accountId, ignored -> new AccountSequence());
		synchronized (accountSequence) {
			long sequenceNumber = transaction.getSequenceNumber();
			CompletedTransaction completed = accountSequence.completed.get(sequenceNumber);
			if (completed != null) {
				return duplicateResult(transaction, completed, accountSequence);
			}

			PendingTransaction pending = accountSequence.pending.get(sequenceNumber);
			if (pending != null) {
				if (sameSequencePayload(pending.transaction(), transaction)) {
					return result(transaction, SequenceProcessingResult.Status.DUPLICATE,
							"sequenceNumber is already pending", accountSequence.nextExpectedSequence,
							missingSequences(accountSequence), pendingSequences(accountSequence), List.of(), List.of());
				}
				return result(transaction, SequenceProcessingResult.Status.CONFLICT,
						"sequenceNumber is already associated with a different transaction",
						accountSequence.nextExpectedSequence, missingSequences(accountSequence),
						pendingSequences(accountSequence), List.of(), List.of());
			}

			if (sequenceNumber < accountSequence.nextExpectedSequence) {
				return result(transaction, SequenceProcessingResult.Status.DUPLICATE,
						"sequenceNumber has already been consumed", accountSequence.nextExpectedSequence,
						missingSequences(accountSequence), pendingSequences(accountSequence), List.of(), List.of());
			}

			accountSequence.pending.put(sequenceNumber, new PendingTransaction(transaction, validation));
			List<SequenceExecution> executions = drain(accountSequence);
			CompletedTransaction completedSubmission = accountSequence.completed.get(sequenceNumber);
			SequenceExecution submittedExecution = completedSubmission == null
					? null
					: completedSubmission.execution();
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

			return result(transaction, status, message, accountSequence.nextExpectedSequence,
					missingSequences(accountSequence), pendingSequences(accountSequence),
					executions, validation.errors());
		}
	}

	public List<SequenceProcessingResult> submitBatch(List<Transaction> requests) {
		Objects.requireNonNull(requests, "requests must not be null");
		return requests.stream().map(this::submit).toList();
	}

	public SequenceProcessingResult recoverPending(String accountId) {
		if (accountId == null || accountId.isBlank()) {
			throw new IllegalArgumentException("accountId must not be blank");
		}
		AccountSequence accountSequence = accountSequences.computeIfAbsent(accountId, ignored -> new AccountSequence());
		synchronized (accountSequence) {
			List<SequenceExecution> executions = drain(accountSequence);
			return new SequenceProcessingResult(
					accountId,
					0,
					SequenceProcessingResult.Status.RECOVERY,
					"Pending records drained where predecessors are available",
					accountSequence.nextExpectedSequence,
					missingSequences(accountSequence),
					pendingSequences(accountSequence),
					executions,
					List.of());
		}
	}

	public List<Transaction> getPendingTransactions(String accountId) {
		if (accountId == null || accountId.isBlank()) {
			throw new IllegalArgumentException("accountId must not be blank");
		}
		AccountSequence accountSequence = accountSequences.computeIfAbsent(accountId, ignored -> new AccountSequence());
		synchronized (accountSequence) {
			return accountSequence.pending.values().stream()
					.map(pending -> copyOf(pending.transaction()))
					.toList();
		}
	}

	private List<SequenceExecution> drain(AccountSequence accountSequence) {
		List<SequenceExecution> executions = new ArrayList<>();
		while (true) {
			PendingTransaction pending = accountSequence.pending.get(accountSequence.nextExpectedSequence);
			if (pending == null) {
				return executions;
			}

			Transaction transaction = pending.transaction();
			SequenceExecution execution;
			if (!pending.validation().isValid()) {
				execution = new SequenceExecution(transaction.getSequenceNumber(),
						transaction.getTransactionId(), true, null, pending.validation().errors());
			} else {
				execution = new SequenceExecution(transaction.getSequenceNumber(),
						transaction.getTransactionId(), false, transactionExecutionService.process(transaction),
						List.of());
			}

			accountSequence.pending.remove(accountSequence.nextExpectedSequence);
			accountSequence.completed.put(accountSequence.nextExpectedSequence,
					new CompletedTransaction(copyOf(transaction), execution));
			executions.add(execution);
			if (accountSequence.nextExpectedSequence == Long.MAX_VALUE) {
				accountSequence.exhausted = true;
				return executions;
			}
			accountSequence.nextExpectedSequence++;
		}
	}

	private static SequenceProcessingResult duplicateResult(
			Transaction transaction, CompletedTransaction completed, AccountSequence accountSequence) {
		if (sameSequencePayload(completed.transaction(), transaction)) {
			return result(transaction, SequenceProcessingResult.Status.DUPLICATE,
					"sequenceNumber has already been processed", accountSequence.nextExpectedSequence,
					missingSequences(accountSequence), pendingSequences(accountSequence), List.of(), List.of());
		}
		return result(transaction, SequenceProcessingResult.Status.CONFLICT,
				"sequenceNumber is already associated with a different transaction",
				accountSequence.nextExpectedSequence, missingSequences(accountSequence),
				pendingSequences(accountSequence), List.of(), List.of());
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

	private static List<SequenceGap> missingSequences(AccountSequence accountSequence) {
		if (accountSequence.exhausted || accountSequence.pending.isEmpty()) {
			return List.of();
		}
		List<SequenceGap> gaps = new ArrayList<>();
		long firstMissing = accountSequence.nextExpectedSequence;
		for (long pendingSequence : accountSequence.pending.keySet()) {
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

	private static List<Long> pendingSequences(AccountSequence accountSequence) {
		return List.copyOf(accountSequence.pending.keySet());
	}

	private static boolean canBeSequenced(Transaction transaction) {
		return transaction != null
				&& transaction.getAccountId() != null
				&& !transaction.getAccountId().isBlank()
				&& transaction.getSequenceNumber() > 0;
	}

	private static boolean sameSequencePayload(PendingTransaction first, Transaction second) {
		return sameSequencePayload(first.transaction(), second);
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

	private static final class AccountSequence {
		private final TreeMap<Long, PendingTransaction> pending = new TreeMap<>();
		private final TreeMap<Long, CompletedTransaction> completed = new TreeMap<>();
		private long nextExpectedSequence = 1;
		private boolean exhausted;
	}

	private record PendingTransaction(Transaction transaction, TransactionValidationResult validation) {
	}

	private record CompletedTransaction(Transaction transaction, SequenceExecution execution) {
	}
}
