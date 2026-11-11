package com.cts.transaction_processor.service;

import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.cts.transaction_processor.model.Transaction;
import com.cts.transaction_processor.model.TransactionStatus;
import com.cts.transaction_processor.model.TransactionType;
import com.cts.transaction_processor.model.Account;
import com.cts.transaction_processor.model.IdempotencyRecord;
import com.cts.transaction_processor.model.RequestIdempotencyRecord;
import com.cts.transaction_processor.repository.AccountRepository;
import com.cts.transaction_processor.repository.IdempotencyRepository;
import com.cts.transaction_processor.repository.RequestIdempotencyRecordJpaRepository;
import com.cts.transaction_processor.repository.TransactionRepository;

@Service
public class IdempotencyService {

	private final IdempotencyRepository idempotencyRepository;
	private final RequestIdempotencyRecordJpaRepository requestRepository;
	private final AccountRepository accountRepository;
	private final TransactionRepository transactionRepository;
	private final TransactionValidationService validationService;
	private final TransientFailureSimulator failureSimulator;

	@Autowired
	public IdempotencyService(IdempotencyRepository idempotencyRepository,
			RequestIdempotencyRecordJpaRepository requestRepository,
			AccountRepository accountRepository, TransactionRepository transactionRepository,
			TransactionValidationService validationService, TransientFailureSimulator failureSimulator) {
		this.idempotencyRepository = Objects.requireNonNull(idempotencyRepository,
				"idempotencyRepository must not be null");
		this.requestRepository = Objects.requireNonNull(requestRepository, "requestRepository must not be null");
		this.accountRepository = Objects.requireNonNull(accountRepository, "accountRepository must not be null");
		this.transactionRepository = Objects.requireNonNull(transactionRepository,
				"transactionRepository must not be null");
		this.validationService = Objects.requireNonNull(validationService, "validationService must not be null");
		this.failureSimulator = Objects.requireNonNull(failureSimulator, "failureSimulator must not be null");
	}

	@Transactional
	public synchronized IdempotencyResult process(Transaction request) {
		Transaction transaction = request == null ? null : copyOf(request);
		TransactionValidationResult validation = validationService.validate(transaction);
		if (!validation.isValid()) {
			return result(IdempotencyResult.Status.INVALID, "Transaction request is invalid", null,
					validation.errors());
		}

		Optional<Transaction> acceptedTransaction = transactionRepository.findById(transaction.getTransactionId());
		if (acceptedTransaction.isPresent()
				&& !sameBusinessPayload(acceptedTransaction.get(), transaction)) {
			return result(IdempotencyResult.Status.CONFLICT,
					"transactionId is already associated with a different payload", null, List.of());
		}

		Optional<IdempotencyRecord> previousTransaction =
				idempotencyRepository.findById(transaction.getTransactionId());
		if (previousTransaction.isPresent()
				&& !sameBusinessPayload(previousTransaction.get().getTransaction(), transaction)) {
			return result(IdempotencyResult.Status.CONFLICT,
					"transactionId is already associated with a different payload", null, List.of());
		}

		Optional<RequestIdempotencyRecord> previousRequest =
				requestRepository.findById(transaction.getRequestId());
		if (previousRequest.isPresent()) {
			if (sameRequestPayload(previousRequest.get().getTransaction(), transaction)) {
				IdempotencyRecord processed = idempotencyRepository
						.findById(previousRequest.get().getTransaction().getTransactionId())
						.orElseThrow(() -> new IllegalStateException("Recorded request has no processed transaction"));
				return result(IdempotencyResult.Status.DUPLICATE_REQUEST,
						"requestId has already been processed", processed.getBalanceAfter(), List.of());
			}
			return result(IdempotencyResult.Status.CONFLICT,
					"requestId is already associated with a different request", null, List.of());
		}

		Optional<Transaction> acceptedRequest = transactionRepository.findByRequestId(transaction.getRequestId());
		if (acceptedRequest.isPresent()
				&& !sameRequestPayload(acceptedRequest.get(), transaction)) {
			return result(IdempotencyResult.Status.CONFLICT,
					"requestId is already associated with a different request", null, List.of());
		}

		if (previousTransaction.isPresent()) {
			IdempotencyRecord processed = previousTransaction.get();
			requestRepository.save(new RequestIdempotencyRecord(
					transaction.getRequestId(), processed.getTransaction()));
			return result(IdempotencyResult.Status.DUPLICATE_TRANSACTION,
					"transactionId has already been processed; the existing balance change was not repeated",
					processed.getBalanceAfter(), List.of());
		}

		BigDecimal balanceDelta;
		if (transaction.getTransactionType() == TransactionType.REVERSAL) {
			Optional<IdempotencyRecord> original =
					idempotencyRepository.findById(transaction.getOriginalTransactionId());
			if (original.isEmpty()) {
				return result(IdempotencyResult.Status.REJECTED,
						"originalTransactionId does not identify a processed transaction", null, List.of());
			}
			IdempotencyRecord originalTransaction = original.get();
			if (!transaction.getAccountId().equals(originalTransaction.getTransaction().getAccountId())
					|| transaction.getAmount().compareTo(originalTransaction.getBalanceDelta().abs()) != 0) {
				return result(IdempotencyResult.Status.CONFLICT,
						"reversal accountId and amount must match the original transaction", null, List.of());
			}
			if (originalTransaction.isReversed()
					|| idempotencyRepository.findByReversedTransactionId(
							transaction.getOriginalTransactionId()).isPresent()) {
				return result(IdempotencyResult.Status.CONFLICT,
						"original transaction has already been reversed", null, List.of());
			}
			balanceDelta = originalTransaction.getBalanceDelta().negate();
		} else {
			balanceDelta = transaction.getTransactionType() == TransactionType.CREDIT
					? transaction.getAmount()
					: transaction.getAmount().negate();
		}

		if (!accountRepository.existsById(transaction.getAccountId())) {
			return result(IdempotencyResult.Status.REJECTED, "accountId does not identify an existing account",
					null, List.of(), transaction.getStatus(), transaction.getRetryCount(), null);
		}

		transactionRepository.save(transaction);
		String failureReason = failureSimulator.failureReason(transaction, transaction.getRetryCount());
		if (failureReason != null) {
			return result(IdempotencyResult.Status.TRANSIENT_FAILURE, failureReason, null,
					List.of(failureReason), transaction.getStatus(), transaction.getRetryCount(), failureReason);
		}

		Account account = accountRepository.findByIdForUpdate(transaction.getAccountId())
				.orElseThrow(() -> new IllegalStateException(
						"Account does not exist: " + transaction.getAccountId()));
		BigDecimal balanceAfter = account.getBalance().add(balanceDelta);
		account.setBalance(balanceAfter);
		try {
			transaction.setStatus(TransactionStatus.PROCESSED);
			transaction.setFailureReason(null);
			transactionRepository.save(transaction);
			if (idempotencyRepository.existsById(transaction.getTransactionId())) {
				throw new IllegalStateException("Transaction already recorded: " + transaction.getTransactionId());
			}
			if (requestRepository.existsById(transaction.getRequestId())) {
				throw new IllegalStateException("Request already recorded: " + transaction.getRequestId());
			}
			idempotencyRepository.save(new IdempotencyRecord(
					transaction,
					balanceDelta,
					balanceAfter,
					transaction.getTransactionType() == TransactionType.REVERSAL
							? transaction.getOriginalTransactionId() : null));
			requestRepository.save(new RequestIdempotencyRecord(transaction.getRequestId(), transaction));
		} catch (RuntimeException exception) {
			account.setBalance(account.getBalance().subtract(balanceDelta));
			transaction.setStatus(TransactionStatus.PROCESSING);
			try {
				transactionRepository.save(transaction);
			} catch (RuntimeException restoreException) {
				exception.addSuppressed(restoreException);
			}
			throw exception;
		}
		return result(IdempotencyResult.Status.PROCESSED, "Transaction processed", balanceAfter, List.of());
	}

	public void recordRetryState(Transaction transaction, TransactionStatus status, String failureReason) {
		transaction.setStatus(status);
		transaction.setFailureReason(failureReason);
		transactionRepository.save(transaction);
	}

	public Optional<RetryState> findRetryState(String transactionId) {
		return transactionRepository.findById(transactionId)
				.filter(transaction -> transaction.getRetryCount() > 0
						|| transaction.getFailureReason() != null
						|| transaction.getStatus() == TransactionStatus.FAILED
						|| transaction.getStatus() == TransactionStatus.INVALID
						|| transaction.getStatus() == TransactionStatus.RETRY_PENDING)
				.map(transaction -> new RetryState(
						transaction,
						transaction.getStatus(),
						transaction.getRetryCount(),
						transaction.getFailureReason()));
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

	public record RetryState(
			Transaction transaction,
			TransactionStatus status,
			int retryCount,
			String failureReason) {
	}
}
