package com.cts.transaction_processor.service;

import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.cts.transaction_processor.model.Transaction;
import com.cts.transaction_processor.model.TransactionStatus;
import com.cts.transaction_processor.model.TransactionType;
import com.cts.transaction_processor.repository.AccountRepository;
import com.cts.transaction_processor.repository.IdempotencyRepository;
import com.cts.transaction_processor.repository.TransactionRepository;
import com.cts.transaction_processor.repository.IdempotencyRepository.ProcessedTransaction;
import com.cts.transaction_processor.repository.IdempotencyRepository.RequestRecord;

@Service
public class IdempotencyService {

	private final IdempotencyRepository idempotencyRepository;
	private final AccountRepository accountRepository;
	private final TransactionRepository transactionRepository;
	private final TransactionValidationService validationService;
	private final TransientFailureSimulator failureSimulator;

	@Autowired
	public IdempotencyService(IdempotencyRepository idempotencyRepository,
			AccountRepository accountRepository, TransactionRepository transactionRepository,
			TransactionValidationService validationService, TransientFailureSimulator failureSimulator) {
		this.idempotencyRepository = Objects.requireNonNull(idempotencyRepository,
				"idempotencyRepository must not be null");
		this.accountRepository = Objects.requireNonNull(accountRepository, "accountRepository must not be null");
		this.transactionRepository = Objects.requireNonNull(transactionRepository,
				"transactionRepository must not be null");
		this.validationService = Objects.requireNonNull(validationService, "validationService must not be null");
		this.failureSimulator = Objects.requireNonNull(failureSimulator, "failureSimulator must not be null");
	}

	public synchronized IdempotencyResult process(Transaction request) {
		Transaction transaction = request == null ? null : copyOf(request);
		TransactionValidationResult validation = validationService.validate(transaction);
		if (!validation.isValid()) {
			return result(IdempotencyResult.Status.INVALID, "Transaction request is invalid", null,
					validation.errors());
		}

		Optional<ProcessedTransaction> previousTransaction =
				idempotencyRepository.findTransaction(transaction.getTransactionId());
		if (previousTransaction.isPresent()
				&& !sameBusinessPayload(previousTransaction.get().transaction(), transaction)) {
			return result(IdempotencyResult.Status.CONFLICT,
					"transactionId is already associated with a different payload", null, List.of());
		}

		Optional<RequestRecord> previousRequest = idempotencyRepository.findRequest(transaction.getRequestId());
		if (previousRequest.isPresent()) {
			if (sameRequestPayload(previousRequest.get().transaction(), transaction)) {
				ProcessedTransaction processed = idempotencyRepository
						.findTransaction(previousRequest.get().transaction().getTransactionId())
						.orElseThrow(() -> new IllegalStateException("Recorded request has no processed transaction"));
				return result(IdempotencyResult.Status.DUPLICATE_REQUEST,
						"requestId has already been processed", processed.balanceAfter(), List.of());
			}
			return result(IdempotencyResult.Status.CONFLICT,
					"requestId is already associated with a different request", null, List.of());
		}

		if (previousTransaction.isPresent()) {
			ProcessedTransaction processed = previousTransaction.get();
			idempotencyRepository.recordRequest(transaction.getRequestId(), transaction);
			return result(IdempotencyResult.Status.DUPLICATE_TRANSACTION,
					"transactionId has already been processed; the existing balance change was not repeated",
					processed.balanceAfter(), List.of());
		}

		BigDecimal balanceDelta;
		if (transaction.getTransactionType() == TransactionType.REVERSAL) {
			Optional<ProcessedTransaction> original = idempotencyRepository
					.findTransaction(transaction.getOriginalTransactionId());
			if (original.isEmpty()) {
				return result(IdempotencyResult.Status.REJECTED,
						"originalTransactionId does not identify a processed transaction", null, List.of());
			}
			ProcessedTransaction originalTransaction = original.get();
			if (!transaction.getAccountId().equals(originalTransaction.transaction().getAccountId())
					|| transaction.getAmount().compareTo(originalTransaction.balanceDelta().abs()) != 0) {
				return result(IdempotencyResult.Status.CONFLICT,
						"reversal accountId and amount must match the original transaction", null, List.of());
			}
			if (idempotencyRepository.isReversed(transaction.getOriginalTransactionId())) {
				return result(IdempotencyResult.Status.CONFLICT,
						"original transaction has already been reversed", null, List.of());
			}
			balanceDelta = originalTransaction.balanceDelta().negate();
		} else {
			balanceDelta = transaction.getTransactionType() == TransactionType.CREDIT
					? transaction.getAmount()
					: transaction.getAmount().negate();
		}

		if (accountRepository.findBalance(transaction.getAccountId()).isEmpty()) {
			return result(IdempotencyResult.Status.REJECTED, "accountId does not identify an existing account",
					null, List.of(), transaction.getStatus(), transaction.getRetryCount(), null);
		}

		transactionRepository.saveOrUpdate(transaction);
		String failureReason = failureSimulator.failureReason(transaction, transaction.getRetryCount());
		if (failureReason != null) {
			return result(IdempotencyResult.Status.TRANSIENT_FAILURE, failureReason, null,
					List.of(failureReason), transaction.getStatus(), transaction.getRetryCount(), failureReason);
		}

		BigDecimal balanceAfter = accountRepository.adjustBalance(transaction.getAccountId(), balanceDelta);
		try {
			transaction.setStatus(TransactionStatus.PROCESSED);
			transactionRepository.saveOrUpdate(transaction);
			idempotencyRepository.recordProcessed(transaction, balanceDelta, balanceAfter,
					transaction.getRequestId(),
					transaction.getTransactionType() == TransactionType.REVERSAL
							? transaction.getOriginalTransactionId() : null);
		} catch (RuntimeException exception) {
			accountRepository.adjustBalance(transaction.getAccountId(), balanceDelta.negate());
			transaction.setStatus(TransactionStatus.PROCESSING);
			try {
				transactionRepository.saveOrUpdate(transaction);
			} catch (RuntimeException restoreException) {
				exception.addSuppressed(restoreException);
			}
			throw exception;
		}
		return result(IdempotencyResult.Status.PROCESSED, "Transaction processed", balanceAfter, List.of());
	}

	public void recordRetryState(Transaction transaction, TransactionStatus status, String failureReason) {
		idempotencyRepository.recordRetryState(transaction, status, failureReason);
		transactionRepository.saveOrUpdate(transaction);
	}

	private static boolean sameRequestPayload(Transaction first, Transaction second) {
		return first.getTransactionId().equals(second.getTransactionId())
				&& sameBusinessPayload(first, second);
	}

	private static boolean sameBusinessPayload(Transaction first, Transaction second) {
		return first.getAccountId().equals(second.getAccountId())
				&& Objects.equals(first.getOriginalTransactionId(), second.getOriginalTransactionId())
				&& first.getTransactionType() == second.getTransactionType()
				&& first.getAmount().compareTo(second.getAmount()) == 0
				&& first.getCurrency().equals(second.getCurrency())
				&& first.getSequenceNumber() == second.getSequenceNumber();
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

	private static IdempotencyResult result(IdempotencyResult.Status status, String message,
			BigDecimal balance, List<String> errors) {
		return result(status, message, balance, errors, null, 0, null);
	}

	private static IdempotencyResult result(IdempotencyResult.Status status, String message,
			BigDecimal balance, List<String> errors,
			com.cts.transaction_processor.model.TransactionStatus transactionStatus,
			int retryCount, String failureReason) {
		return new IdempotencyResult(status, message, balance, errors, transactionStatus, retryCount, failureReason);
	}
}
