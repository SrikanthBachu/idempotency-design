package com.cts.transaction_processor.service;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.cts.transaction_processor.model.Transaction;
import com.cts.transaction_processor.model.TransactionStatus;
import com.cts.transaction_processor.model.TransactionType;
import com.cts.transaction_processor.repository.AccountRepository;
import com.cts.transaction_processor.repository.IdempotencyRepository;
import com.cts.transaction_processor.repository.TransactionRepository;

import jakarta.validation.Validation;
import jakarta.validation.ValidatorFactory;

class IdempotencyServiceTests {

	private static ValidatorFactory validatorFactory;
	private AccountRepository accountRepository;
	private IdempotencyService idempotencyService;

	@BeforeAll
	static void createValidatorFactory() {
		validatorFactory = Validation.buildDefaultValidatorFactory();
	}

	@AfterAll
	static void closeValidatorFactory() {
		validatorFactory.close();
	}

	@BeforeEach
	void createService() {
		accountRepository = new AccountRepository();
		accountRepository.saveBalance("account-1", new BigDecimal("100.00"));
		idempotencyService = new IdempotencyService(
				new IdempotencyRepository(),
				accountRepository,
				new TransactionRepository(),
				new TransactionValidationService(validatorFactory.getValidator()),
				(transaction, retryCount) -> null);
	}

	@Test
	void repeatedRequestIdDoesNotApplyTheBalanceChangeAgain() {
		IdempotencyResult first = idempotencyService.process(transaction("txn-1", "request-1", "10.00"));
		IdempotencyResult retry = idempotencyService.process(transaction("txn-1", "request-1", "10.00"));

		assertEquals(IdempotencyResult.Status.PROCESSED, first.status());
		assertEquals(IdempotencyResult.Status.DUPLICATE_REQUEST, retry.status());
		assertEquals(new BigDecimal("110.00"), accountRepository.findBalance("account-1").orElseThrow());
	}

	@Test
	void newRequestIdForAnExistingTransactionIsBusinessDuplicate() {
		idempotencyService.process(transaction("txn-1", "request-1", "10.00"));

		IdempotencyResult duplicate =
				idempotencyService.process(transaction("txn-1", "request-2", "10.00"));

		assertEquals(IdempotencyResult.Status.DUPLICATE_TRANSACTION, duplicate.status());
		assertEquals(new BigDecimal("110.00"), accountRepository.findBalance("account-1").orElseThrow());
	}

	@Test
	void reusedTransactionIdWithDifferentPayloadIsConflict() {
		idempotencyService.process(transaction("txn-1", "request-1", "10.00"));

		IdempotencyResult conflict =
				idempotencyService.process(transaction("txn-1", "request-2", "12.00"));

		assertEquals(IdempotencyResult.Status.CONFLICT, conflict.status());
		assertEquals("transactionId is already associated with a different payload", conflict.message());
		assertEquals(new BigDecimal("110.00"), accountRepository.findBalance("account-1").orElseThrow());
	}

	@Test
	void concurrentRequestsForTheSameTransactionApplyTheBalanceChangeOnce() throws Exception {
		int requestCount = 20;
		ExecutorService executor = Executors.newFixedThreadPool(8);
		CountDownLatch ready = new CountDownLatch(8);
		CountDownLatch start = new CountDownLatch(1);
		try {
			List<Future<IdempotencyResult>> futures = new ArrayList<>();
			for (int index = 0; index < requestCount; index++) {
				String requestId = "request-" + index;
				futures.add(executor.submit(() -> {
					ready.countDown();
					if (!start.await(5, TimeUnit.SECONDS)) {
						throw new IllegalStateException("Timed out waiting to start concurrent requests");
					}
					return idempotencyService.process(transaction("txn-1", requestId, "10.00"));
				}));
			}

			if (!ready.await(5, TimeUnit.SECONDS)) {
				throw new IllegalStateException("Concurrent requests did not become ready");
			}
			start.countDown();

			List<IdempotencyResult.Status> statuses = new ArrayList<>();
			for (Future<IdempotencyResult> future : futures) {
				statuses.add(future.get(5, TimeUnit.SECONDS).status());
			}
			assertEquals(1, statuses.stream().filter(status -> status == IdempotencyResult.Status.PROCESSED).count());
			assertEquals(requestCount - 1L, statuses.stream()
					.filter(status -> status == IdempotencyResult.Status.DUPLICATE_TRANSACTION).count());
			assertEquals(new BigDecimal("110.00"), accountRepository.findBalance("account-1").orElseThrow());
		} finally {
			executor.shutdownNow();
		}
	}

	@Test
	void reversalAppliesTheInverseOfTheOriginalBalanceChangeOnlyOnce() {
		idempotencyService.process(transaction("txn-1", "request-1", "10.00"));
		Transaction reversal = new Transaction(
				"reversal-1", "request-2", "account-1", "txn-1", TransactionType.REVERSAL,
				new BigDecimal("10.00"), "USD", 2, TransactionStatus.PENDING, 0,
				Instant.parse("2026-01-01T00:00:00Z"), Instant.parse("2026-01-01T00:00:00Z"));

		IdempotencyResult processed = idempotencyService.process(reversal);
		IdempotencyResult retry = idempotencyService.process(reversal);

		assertEquals(IdempotencyResult.Status.PROCESSED, processed.status());
		assertEquals(IdempotencyResult.Status.DUPLICATE_REQUEST, retry.status());
		assertEquals(new BigDecimal("100.00"), accountRepository.findBalance("account-1").orElseThrow());
	}

	private static Transaction transaction(String transactionId, String requestId, String amount) {
		Instant now = Instant.parse("2026-01-01T00:00:00Z");
		return new Transaction(
				transactionId, requestId, "account-1", TransactionType.CREDIT, new BigDecimal(amount),
				"USD", 1, TransactionStatus.PENDING, 0, now, now);
	}
}
