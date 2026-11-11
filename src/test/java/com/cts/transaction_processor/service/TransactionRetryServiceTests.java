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
import com.cts.transaction_processor.service.IdempotencyService.RetryState;

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
		TestPersistence.Context persistence = persistence();
		var accounts = persistence.accounts();
		var idempotencyRepository = persistence.idempotency();
		var transactionRepository = persistence.transactions();
		AtomicInteger attempts = new AtomicInteger();
		IdempotencyService executor = new IdempotencyService(
				idempotencyRepository,
				persistence.requests(),
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
		RetryState retryState = executor.findRetryState("TXN-2005").orElseThrow();
		assertEquals(TransactionStatus.PROCESSED, retryState.status());
		assertEquals(1, retryState.retryCount());
		assertEquals(TransactionStatus.PROCESSED,
				transactionRepository.findById("TXN-2005").orElseThrow().getStatus());
		assertEquals(new BigDecimal("110.00"), TestPersistence.balance(accounts, "account-1"));
	}

	@Test
	void permanentValidationErrorsAreNotRetried() {
		TestPersistence.Context persistence = persistence();
		var accounts = persistence.accounts();
		var idempotencyRepository = persistence.idempotency();
		var transactionRepository = persistence.transactions();
		AtomicInteger attempts = new AtomicInteger();
		IdempotencyService executor = new IdempotencyService(
				idempotencyRepository,
				persistence.requests(),
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
				executor.findRetryState("TXN-INVALID").orElseThrow().status());
		assertEquals(new BigDecimal("100.00"), TestPersistence.balance(accounts, "account-1"));
	}

	@Test
	void marksTransactionFailedAfterThreeRetriesAreExhausted() {
		TestPersistence.Context persistence = persistence();
		var accounts = persistence.accounts();
		var idempotencyRepository = persistence.idempotency();
		var transactionRepository = persistence.transactions();
		java.util.concurrent.atomic.AtomicInteger attempts = new java.util.concurrent.atomic.AtomicInteger();
		IdempotencyService executor = new IdempotencyService(
				idempotencyRepository,
				persistence.requests(),
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
				executor.findRetryState("TXN-EXHAUST").orElseThrow().status());
		assertEquals(TransactionStatus.FAILED,
				transactionRepository.findById("TXN-EXHAUST").orElseThrow().getStatus());
		assertEquals(new BigDecimal("100.00"), TestPersistence.balance(accounts, "account-1"));
	}

	private static TestPersistence.Context persistence() {
		TestPersistence.Context persistence = TestPersistence.create();
		TestPersistence.saveAccount(persistence.accounts(), "account-1", new BigDecimal("100.00"));
		return persistence;
	}

	private static Transaction transaction(String transactionId) {
		Instant now = Instant.parse("2026-01-01T00:00:00Z");
		return new Transaction(
				transactionId, "request-" + transactionId, "account-1", TransactionType.CREDIT,
				new BigDecimal("10.00"), "USD", 1, TransactionStatus.PENDING, 0, now, now);
	}
}
