package com.cts.transaction_processor.api;

import java.math.BigDecimal;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;

import com.cts.transaction_processor.api.dto.TransactionStatusDto;
import com.cts.transaction_processor.api.dto.TransactionSummaryDto;
import com.cts.transaction_processor.model.Transaction;
import com.cts.transaction_processor.model.TransactionStatus;
import com.cts.transaction_processor.repository.AccountRepository;
import com.cts.transaction_processor.repository.IdempotencyRepository;
import com.cts.transaction_processor.repository.IdempotencyRepository.RetryState;
import com.cts.transaction_processor.repository.TransactionRepository;
import com.cts.transaction_processor.repository.TransactionResultRepository;
import com.cts.transaction_processor.service.IdempotencyResult;
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
	private final IdempotencyRepository idempotencyRepository;
	private final TransactionRetryService retryService;
	private final TransactionResultRepository resultRepository;

	public TransactionApiService(
			TransactionProcessor processor,
			TransactionRepository transactionRepository,
			AccountRepository accountRepository,
			IdempotencyRepository idempotencyRepository,
			TransactionRetryService retryService,
			TransactionResultRepository resultRepository) {
		this.processor = processor;
		this.transactionRepository = transactionRepository;
		this.accountRepository = accountRepository;
		this.idempotencyRepository = idempotencyRepository;
		this.retryService = retryService;
		this.resultRepository = resultRepository;
	}

	public TransactionStatusDto findTransaction(String transactionId) {
		Transaction transaction = transactionRepository.findById(transactionId)
				.orElseThrow(() -> new ResourceNotFoundException("Transaction not found: " + transactionId));
		String failureReason = idempotencyRepository.findRetryState(transactionId)
				.map(RetryState::failureReason)
				.orElseGet(() -> failureReasonFor(transaction));
		return new TransactionStatusDto(
				transaction.getTransactionId(),
				transaction.getAccountId(),
				transaction.getSequenceNumber(),
				transaction.getTransactionType(),
				transaction.getAmount(),
				transaction.getCurrency(),
				transaction.getStatus(),
				transaction.getRetryCount(),
				failureReason);
	}

	public SequenceProcessingResult process(Transaction transaction) {
		if (hasTransactionId(transaction)
				&& transactionRepository.findById(transaction.getTransactionId()).isEmpty()) {
			transaction.setStatus(TransactionStatus.RECEIVED);
			transactionRepository.saveOrUpdate(transaction);
		}

		SequenceProcessingResult result = processor.process(transaction);
		for (SequenceExecution execution : result.executions()) {
			Transaction stored = execution.transactionId() == null || execution.transactionId().isBlank()
					? null
					: transactionRepository.findById(execution.transactionId()).orElse(null);
			if (stored != null) {
				stored.setStatus(statusOf(execution));
				if (execution.executionResult() != null) {
					stored.setRetryCount(execution.executionResult().retryCount());
				}
				transactionRepository.saveOrUpdate(stored);
			}
		}
		if (hasTransactionId(transaction) && result.executions().stream()
				.noneMatch(execution -> transaction.getTransactionId().equals(execution.transactionId()))
				&& isTerminalFailure(result.status())) {
			Transaction stored = transactionRepository.findById(transaction.getTransactionId()).orElse(null);
			if (stored != null) {
				stored.setStatus(TransactionStatus.FAILED);
				transactionRepository.saveOrUpdate(stored);
			}
		}
		return result;
	}

	private static boolean hasTransactionId(Transaction transaction) {
		return transaction != null
				&& transaction.getTransactionId() != null
				&& !transaction.getTransactionId().isBlank();
	}

	private String failureReasonFor(Transaction transaction) {
		if (transaction.getAccountId() == null || transaction.getAccountId().isBlank()
				|| transaction.getSequenceNumber() <= 0) {
			return null;
		}
		return processor.findExecution(transaction.getAccountId(), transaction.getSequenceNumber())
				.map(execution -> {
					if (execution.terminalInvalid()) {
						return String.join("; ", execution.errors());
					}
					return execution.executionResult() != null
							&& execution.executionResult().status() == IdempotencyResult.Status.FAILED
							? execution.executionResult().failureReason()
							: null;
				})
				.orElse(null);
	}

	private static boolean isTerminalFailure(Status status) {
		return status == Status.FAILED
				|| status == Status.UNSEQUENCABLE
				|| status == Status.CONFLICT
				|| status == Status.REJECTED
				|| status == Status.INVALID;
	}

	public BigDecimal findBalance(String accountId) {
		return accountRepository.findBalance(accountId)
				.orElseThrow(() -> new ResourceNotFoundException("Account not found: " + accountId));
	}

	public TransactionSummaryDto summarize() {
		Map<TransactionStatus, Long> counts = new EnumMap<>(TransactionStatus.class);
		for (TransactionStatus status : TransactionStatus.values()) {
			counts.put(status, 0L);
		}
		List<Transaction> transactions = transactionRepository.findAll();
		for (Transaction transaction : transactions) {
			if (transaction.getStatus() != null) {
				counts.compute(transaction.getStatus(), (status, count) -> count + 1);
			}
		}
		return new TransactionSummaryDto(transactions.size(), counts);
	}

	public SequenceProcessingResult retryFailed(String transactionId) {
		Transaction transaction = transactionRepository.findById(transactionId)
				.orElseThrow(() -> new ResourceNotFoundException("Transaction not found: " + transactionId));
		RetryState retryState = idempotencyRepository.findRetryState(transactionId)
				.orElseThrow(() -> new ConflictException("Transaction is not eligible for retry"));
		if (transaction.getStatus() != TransactionStatus.FAILED
				|| retryState.status() != TransactionStatus.FAILED) {
			throw new ConflictException("Only transactions that exhausted transient retries can be retried");
		}

		transaction.setStatus(TransactionStatus.PENDING);
		transaction.setRetryCount(0);
		transactionRepository.saveOrUpdate(transaction);
		IdempotencyResult retryResult = retryService.process(transaction);
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
}
