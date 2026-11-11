package com.cts.transaction_processor.api;

import java.math.BigDecimal;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeSet;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.cts.transaction_processor.api.dto.TransactionStatusDto;
import com.cts.transaction_processor.api.dto.TransactionSummaryDto;
import com.cts.transaction_processor.model.Transaction;
import com.cts.transaction_processor.model.TransactionStatus;
import com.cts.transaction_processor.model.Account;
import com.cts.transaction_processor.repository.AccountRepository;
import com.cts.transaction_processor.repository.TransactionRepository;
import com.cts.transaction_processor.repository.TransactionResultRepository;
import com.cts.transaction_processor.service.IdempotencyResult;
import com.cts.transaction_processor.service.IdempotencyService;
import com.cts.transaction_processor.service.IdempotencyService.RetryState;
import com.cts.transaction_processor.service.SequenceExecution;
import com.cts.transaction_processor.service.SequenceProcessingResult;
import com.cts.transaction_processor.service.SequenceProcessingResult.Status;
import com.cts.transaction_processor.service.TransactionProcessor;
import com.cts.transaction_processor.service.TransactionRetryService;

@Service
public class TransactionApiService {

	private final TransactionProcessor processor;
	private final TransactionRepository transactionRepository;
	private final AccountRepository accountRepository;
	private final IdempotencyService idempotencyService;
	private final TransactionRetryService retryService;
	private final TransactionResultRepository resultRepository;

	public TransactionApiService(
			TransactionProcessor processor,
			TransactionRepository transactionRepository,
			AccountRepository accountRepository,
			IdempotencyService idempotencyService,
			TransactionRetryService retryService,
			TransactionResultRepository resultRepository) {
		this.processor = processor;
		this.transactionRepository = transactionRepository;
		this.accountRepository = accountRepository;
		this.idempotencyService = idempotencyService;
		this.retryService = retryService;
		this.resultRepository = resultRepository;
	}

	public TransactionStatusDto findTransaction(String transactionId) {
		Transaction transaction = transactionRepository.findById(transactionId)
				.orElseThrow(() -> new ResourceNotFoundException("Transaction not found: " + transactionId));
		return new TransactionStatusDto(
				transaction.getTransactionId(),
				transaction.getAccountId(),
				transaction.getSequenceNumber(),
				transaction.getTransactionType(),
				transaction.getAmount(),
				transaction.getCurrency(),
				transaction.getStatus(),
				transaction.getRetryCount(),
				transaction.getFailureReason());
	}

	public SequenceProcessingResult process(Transaction transaction) {
		acceptTransaction(transaction);

		SequenceProcessingResult result = processor.process(transaction);
		for (SequenceExecution execution : result.executions()) {
			Transaction stored = execution.transactionId() == null || execution.transactionId().isBlank()
					? null
					: transactionRepository.findById(execution.transactionId()).orElse(null);
			if (stored != null) {
				stored.setStatus(statusOf(execution));
				stored.setFailureReason(failureReasonOf(execution));
				if (execution.executionResult() != null) {
					stored.setRetryCount(execution.executionResult().retryCount());
				}
				transactionRepository.save(stored);
			}
		}
		if (hasTransactionId(transaction) && result.executions().stream()
				.noneMatch(execution -> transaction.getTransactionId().equals(execution.transactionId()))
				&& isTerminalFailure(result.status())) {
			Transaction stored = transactionRepository.findById(transaction.getTransactionId()).orElse(null);
			if (stored != null) {
				stored.setStatus(TransactionStatus.FAILED);
				stored.setFailureReason(result.errors().isEmpty()
						? result.message()
						: String.join("; ", result.errors()));
				transactionRepository.save(stored);
			}
		}
		return result;
	}

	@Transactional
	public void acceptBatch(List<Transaction> transactions) {
		TreeSet<String> accountIds = new TreeSet<>();
		for (Transaction transaction : transactions) {
			if (transaction != null && transaction.getAccountId() != null
					&& !transaction.getAccountId().isBlank()) {
				accountIds.add(transaction.getAccountId());
			}
		}
		for (String accountId : accountIds) {
			accountRepository.findByIdForUpdate(accountId);
		}
		for (Transaction transaction : transactions) {
			acceptTransaction(transaction);
		}
	}

	private synchronized void acceptTransaction(Transaction transaction) {
		if (!hasTransactionId(transaction)) {
			return;
		}
		Transaction requestWithSameId = transaction.getRequestId() == null
				? null
				: transactionRepository.findByRequestId(transaction.getRequestId()).orElse(null);
		if (requestWithSameId != null && !sameRequestPayload(requestWithSameId, transaction)) {
			throw new ConflictException("requestId is already associated with a different request");
		}
		Transaction existing = transactionRepository.findById(transaction.getTransactionId()).orElse(null);
		if (existing != null) {
			if (!sameBusinessPayload(existing, transaction)) {
				throw new ConflictException(
						"transactionId is already associated with a different payload");
			}
			return;
		}
		transaction.setStatus(TransactionStatus.RECEIVED);
		transactionRepository.saveAndFlush(transaction);
	}

	private static boolean sameRequestPayload(Transaction first, Transaction second) {
		return first.getTransactionId().equals(second.getTransactionId())
				&& sameBusinessPayload(first, second);
	}

	private static boolean sameBusinessPayload(Transaction first, Transaction second) {
		return Objects.equals(first.getAccountId(), second.getAccountId())
				&& Objects.equals(first.getOriginalTransactionId(), second.getOriginalTransactionId())
				&& first.getTransactionType() == second.getTransactionType()
				&& sameAmount(first.getAmount(), second.getAmount())
				&& Objects.equals(first.getCurrency(), second.getCurrency())
				&& first.getSequenceNumber() == second.getSequenceNumber();
	}

	private static boolean sameAmount(BigDecimal first, BigDecimal second) {
		return first == null ? second == null : second != null && first.compareTo(second) == 0;
	}

	private static boolean hasTransactionId(Transaction transaction) {
		return transaction != null
				&& transaction.getTransactionId() != null
				&& !transaction.getTransactionId().isBlank();
	}

	private static boolean isTerminalFailure(Status status) {
		return status == Status.FAILED
				|| status == Status.UNSEQUENCABLE
				|| status == Status.CONFLICT
				|| status == Status.REJECTED
				|| status == Status.INVALID;
	}

	public BigDecimal findBalance(String accountId) {
		return accountRepository.findById(accountId)
				.map(Account::getBalance)
				.orElseThrow(() -> new ResourceNotFoundException("Account not found: " + accountId));
	}

	public TransactionSummaryDto summarize() {
		Map<TransactionStatus, Long> counts = new EnumMap<>(TransactionStatus.class);
		for (TransactionStatus status : TransactionStatus.values()) {
			counts.put(status, 0L);
		}
		long total = 0;
		for (TransactionRepository.StatusCount statusCount : transactionRepository.countGroupedByStatus()) {
			counts.put(statusCount.getStatus(), statusCount.getTransactionCount());
			total += statusCount.getTransactionCount();
		}
		return new TransactionSummaryDto(total, counts);
	}

	@Transactional
	public SequenceProcessingResult retryFailed(String transactionId) {
		Transaction transaction = transactionRepository.findById(transactionId)
				.orElseThrow(() -> new ResourceNotFoundException("Transaction not found: " + transactionId));
		RetryState retryState = idempotencyService.findRetryState(transactionId)
				.orElseThrow(() -> new ConflictException("Transaction is not eligible for retry"));
		if (transaction.getStatus() != TransactionStatus.FAILED
				|| retryState.status() != TransactionStatus.FAILED
				|| retryState.retryCount() < TransactionRetryService.MAX_RETRIES) {
			throw new ConflictException("Only transactions that exhausted transient retries can be retried");
		}

		transaction.setStatus(TransactionStatus.PENDING);
		transaction.setRetryCount(0);
		transactionRepository.save(transaction);
		IdempotencyResult retryResult = retryService.process(transaction);
		if (retryResult.status() == IdempotencyResult.Status.DUPLICATE_REQUEST
				|| retryResult.status() == IdempotencyResult.Status.DUPLICATE_TRANSACTION) {
			transaction.setStatus(TransactionStatus.PROCESSED);
			transaction.setFailureReason(null);
			transactionRepository.save(transaction);
		}
		SequenceExecution execution = new SequenceExecution(
				transaction.getSequenceNumber(),
				transaction.getTransactionId(),
				false,
				retryResult,
				retryResult.errors());
		SequenceProcessingResult result = toSequenceResult(transaction, retryResult, execution);
		resultRepository.save(transaction, result);
		return result;
	}

	private static SequenceProcessingResult toSequenceResult(
			Transaction transaction, IdempotencyResult retryResult, SequenceExecution execution) {
		SequenceProcessingResult.Status status = switch (retryResult.status()) {
			case PROCESSED -> SequenceProcessingResult.Status.PROCESSED;
			case DUPLICATE_REQUEST, DUPLICATE_TRANSACTION -> SequenceProcessingResult.Status.DUPLICATE;
			case CONFLICT -> SequenceProcessingResult.Status.CONFLICT;
			case INVALID -> SequenceProcessingResult.Status.INVALID;
			case FAILED, TRANSIENT_FAILURE -> SequenceProcessingResult.Status.FAILED;
			case REJECTED -> SequenceProcessingResult.Status.REJECTED;
		};
		return new SequenceProcessingResult(
				transaction.getAccountId(),
				transaction.getSequenceNumber(),
				status,
				retryResult.message(),
				transaction.getSequenceNumber() + 1,
				List.of(),
				List.of(),
				List.of(execution),
				retryResult.errors());
	}

	private static TransactionStatus statusOf(SequenceExecution execution) {
		if (execution.terminalInvalid()) {
			return TransactionStatus.FAILED;
		}
		return switch (execution.executionResult().status()) {
			case PROCESSED -> TransactionStatus.PROCESSED;
			case DUPLICATE_REQUEST, DUPLICATE_TRANSACTION -> TransactionStatus.DUPLICATE;
			case TRANSIENT_FAILURE, FAILED, INVALID, CONFLICT, REJECTED -> TransactionStatus.FAILED;
		};
	}

	private static String failureReasonOf(SequenceExecution execution) {
		if (execution.terminalInvalid()) {
			return String.join("; ", execution.errors());
		}
		if (execution.executionResult() == null) {
			return null;
		}
		if (execution.executionResult().failureReason() != null) {
			return execution.executionResult().failureReason();
		}
		return execution.executionResult().errors().isEmpty()
				? null
				: String.join("; ", execution.executionResult().errors());
	}
}
