package com.cts.transaction_processor.service;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.cts.transaction_processor.model.Transaction;
import com.cts.transaction_processor.model.TransactionStatus;
import com.cts.transaction_processor.model.TransactionType;
import com.cts.transaction_processor.repository.AccountRepository;
import com.cts.transaction_processor.repository.IdempotencyRepository;
import com.cts.transaction_processor.repository.IdempotencyRepository.RetryState;
import com.cts.transaction_processor.repository.TransactionRepository;

import jakarta.validation.Validation;
import jakarta.validation.ValidatorFactory;

class TransactionRetryServiceTests {

	private static ValidatorFactory validatorFactory;

	@BeforeAll
	static void createValidatorFactory() {
		validatorFactory = Validation.buildDefaultValidatorFactory();
	}

	@AfterAll
	static void closeValidatorFactory() {
		validatorFactory.close();
	}

	@Test
	void retriesSimulatedFailureAndProcessesTheBalanceOnce() {
		AccountRepository accounts = accountRepository();
		IdempotencyRepository idempotencyRepository = new IdempotencyRepository();
		TransactionRepository transactionRepository = new TransactionRepository();
		AtomicInteger attempts = new AtomicInteger();
		IdempotencyService executor = new IdempotencyService(
				idempotencyRepository,
				accounts,
				transactionRepository,
				new TransactionValidationService(validatorFactory.getValidator()),
				(transaction, retryCount) -> {
					attempts.incrementAndGet();
					return retryCount == 0 ? "Simulated transient failure" : null;
				});
		TransactionRetryService retryService = new TransactionRetryService(executor);
		Transaction transaction = transaction("TXN-2005");

		IdempotencyResult result = retryService.process(transaction);
		Transaction duplicateRequest = transaction("TXN-2005");
		duplicateRequest.setRequestId("request-TXN-2005-retry");
		IdempotencyResult duplicate = retryService.process(duplicateRequest);

		assertEquals(IdempotencyResult.Status.PROCESSED, result.status());
		assertEquals(TransactionStatus.PROCESSED, result.transactionStatus());
		assertEquals(1, result.retryCount());
		assertEquals(1, transaction.getRetryCount());
		assertEquals(TransactionStatus.PROCESSED, transaction.getStatus());
		assertEquals(2, attempts.get());
		assertEquals(IdempotencyResult.Status.DUPLICATE_TRANSACTION, duplicate.status());
		RetryState retryState = idempotencyRepository.findRetryState("TXN-2005").orElseThrow();
		assertEquals(TransactionStatus.PROCESSED, retryState.status());
		assertEquals(1, retryState.retryCount());
		assertEquals(TransactionStatus.PROCESSED,
				transactionRepository.findById("TXN-2005").orElseThrow().getStatus());
		assertEquals(new BigDecimal("110.00"), accounts.findBalance("account-1").orElseThrow());
	}

	@Test
	void permanentValidationErrorsAreNotRetried() {
		AccountRepository accounts = accountRepository();
		IdempotencyRepository idempotencyRepository = new IdempotencyRepository();
		TransactionRepository transactionRepository = new TransactionRepository();
		AtomicInteger attempts = new AtomicInteger();
		IdempotencyService executor = new IdempotencyService(
				idempotencyRepository,
				accounts,
				transactionRepository,
				new TransactionValidationService(validatorFactory.getValidator()),
				(transaction, retryCount) -> {
					attempts.incrementAndGet();
					return "Should not be reached for invalid transactions";
				});
		TransactionRetryService retryService = new TransactionRetryService(executor);
		Transaction transaction = transaction("TXN-INVALID");
		transaction.setAmount(BigDecimal.ZERO);

		IdempotencyResult result = retryService.process(transaction);

		assertEquals(IdempotencyResult.Status.INVALID, result.status());
		assertEquals(TransactionStatus.INVALID, result.transactionStatus());
		assertEquals(TransactionStatus.INVALID, transaction.getStatus());
		assertEquals(0, result.retryCount());
		assertEquals(0, attempts.get());
		assertEquals(TransactionStatus.INVALID,
				idempotencyRepository.findRetryState("TXN-INVALID").orElseThrow().status());
		assertEquals(new BigDecimal("100.00"), accounts.findBalance("account-1").orElseThrow());
	}

	@Test
	void marksTransactionFailedAfterThreeRetriesAreExhausted() {
		AccountRepository accounts = accountRepository();
		IdempotencyRepository idempotencyRepository = new IdempotencyRepository();
		TransactionRepository transactionRepository = new TransactionRepository();
		java.util.concurrent.atomic.AtomicInteger attempts = new java.util.concurrent.atomic.AtomicInteger();
		IdempotencyService executor = new IdempotencyService(
				idempotencyRepository,
				accounts,
				transactionRepository,
				new TransactionValidationService(validatorFactory.getValidator()),
				(transaction, retryCount) -> {
					attempts.incrementAndGet();
					return "Simulated persistent downstream timeout";
				});
		TransactionRetryService retryService = new TransactionRetryService(executor);
		Transaction transaction = transaction("TXN-EXHAUST");

		IdempotencyResult result = retryService.process(transaction);

		assertEquals(IdempotencyResult.Status.FAILED, result.status());
		assertEquals(TransactionStatus.FAILED, result.transactionStatus());
		assertEquals(TransactionStatus.FAILED, transaction.getStatus());
		assertEquals(TransactionRetryService.MAX_RETRIES, result.retryCount());
		assertEquals("Simulated persistent downstream timeout", result.failureReason());
		assertEquals(TransactionRetryService.MAX_RETRIES + 1, attempts.get());
		assertEquals(TransactionStatus.FAILED,
				idempotencyRepository.findRetryState("TXN-EXHAUST").orElseThrow().status());
		assertEquals(TransactionStatus.FAILED,
				transactionRepository.findById("TXN-EXHAUST").orElseThrow().getStatus());
		assertEquals(new BigDecimal("100.00"), accounts.findBalance("account-1").orElseThrow());
	}

	private static AccountRepository accountRepository() {
		AccountRepository accounts = new AccountRepository();
		accounts.saveBalance("account-1", new BigDecimal("100.00"));
		return accounts;
	}

	private static Transaction transaction(String transactionId) {
		Instant now = Instant.parse("2026-01-01T00:00:00Z");
		return new Transaction(
				transactionId, "request-" + transactionId, "account-1", TransactionType.CREDIT,
				new BigDecimal("10.00"), "USD", 1, TransactionStatus.PENDING, 0, now, now);
	}
}
