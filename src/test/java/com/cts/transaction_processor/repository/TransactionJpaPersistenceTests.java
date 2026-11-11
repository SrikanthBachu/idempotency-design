package com.cts.transaction_processor.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;

import com.cts.transaction_processor.model.Transaction;
import com.cts.transaction_processor.model.TransactionStatus;
import com.cts.transaction_processor.model.TransactionType;
import com.cts.transaction_processor.model.Account;
import com.cts.transaction_processor.api.TransactionApiService;
import com.cts.transaction_processor.api.ConflictException;
import com.cts.transaction_processor.service.IdempotencyResult;
import com.cts.transaction_processor.service.IdempotencyService;
import com.cts.transaction_processor.service.SequenceProcessingResult;

@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:transaction-persistence-tests;DB_CLOSE_DELAY=-1")
@AutoConfigureMockMvc
class TransactionJpaPersistenceTests {

	@Autowired
	private AccountRepository accounts;

	@Autowired
	private TransactionRepository transactions;

	@Autowired
	private IdempotencyRepository idempotencyRepository;

	@Autowired
	private RequestIdempotencyRecordJpaRepository requestRecords;

	@Autowired
	private IdempotencyService idempotencyService;

	@Autowired
	private TransactionApiService transactionApiService;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Autowired
	private MockMvc mockMvc;

	@Test
	void persistsAccountTransactionAndBothIdempotencyKeys() {
		String suffix = UUID.randomUUID().toString();
		String accountId = "account-" + suffix;
		String transactionId = "transaction-" + suffix;
		String requestId = "request-" + suffix;
		BigDecimal initialBalance = new BigDecimal("100.00");
		accounts.save(new Account(accountId, "USD", initialBalance));

		Instant now = Instant.now();
		Transaction transaction = new Transaction(
				transactionId, requestId, accountId, TransactionType.CREDIT,
				new BigDecimal("12.50"), "USD", 1, TransactionStatus.PENDING, 0, now, now);

		IdempotencyResult processed = idempotencyService.process(transaction);
		IdempotencyResult duplicateRequest = idempotencyService.process(transaction);
		Transaction businessDuplicate = new Transaction(
				transactionId, requestId + "-retry", accountId, TransactionType.CREDIT,
				new BigDecimal("12.50"), "USD", 1, TransactionStatus.PENDING, 0, now, now);
		IdempotencyResult duplicateTransaction = idempotencyService.process(businessDuplicate);

		assertEquals(IdempotencyResult.Status.PROCESSED, processed.status());
		assertEquals(IdempotencyResult.Status.DUPLICATE_REQUEST, duplicateRequest.status());
		assertEquals(IdempotencyResult.Status.DUPLICATE_TRANSACTION, duplicateTransaction.status());
		assertEquals(new BigDecimal("112.5000"), accounts.findById(accountId).orElseThrow().getBalance());
		assertEquals(TransactionStatus.PROCESSED, transactions.findById(transactionId).orElseThrow().getStatus());
		assertEquals(new BigDecimal("12.5000"),
				idempotencyRepository.findById(transactionId).orElseThrow().getBalanceDelta());
		assertEquals(transactionId, requestRecords.findById(requestId).orElseThrow()
				.getTransaction().getTransactionId());
		assertEquals(transactionId, requestRecords.findById(requestId + "-retry").orElseThrow()
				.getTransaction().getTransactionId());
	}

	@Test
	void duplicateTransactionThroughProcessingApiDoesNotChangeBalanceAgain() {
		String suffix = UUID.randomUUID().toString();
		String accountId = "account-" + suffix;
		String transactionId = "duplicate-" + suffix;
		accounts.save(new Account(accountId, "USD", new BigDecimal("100.00")));
		Transaction original = transaction(
				transactionId, "request-original-" + suffix, accountId, 1, "12.50");

		SequenceProcessingResult first = transactionApiService.process(original);
		SequenceProcessingResult duplicate = transactionApiService.process(transaction(
				transactionId, "request-duplicate-" + suffix, accountId, 1, "12.50"));

		assertEquals(SequenceProcessingResult.Status.PROCESSED, first.status());
		assertEquals(SequenceProcessingResult.Status.DUPLICATE, duplicate.status());
		assertEquals(TransactionStatus.PROCESSED,
				transactions.findById(transactionId).orElseThrow().getStatus());
		assertEquals(new BigDecimal("112.5000"), accounts.findById(accountId).orElseThrow().getBalance());
	}

	@Test
	void persistsTerminalInvalidTransactionWithoutRejectingItAtTheDatabase() {
		String suffix = UUID.randomUUID().toString();
		String accountId = "account-" + suffix;
		String transactionId = "invalid-" + suffix;
		accounts.save(new Account(accountId, "USD", new BigDecimal("100.00")));
		Instant now = Instant.now();
		Transaction invalid = new Transaction(
				transactionId, "request-" + suffix, accountId, TransactionType.CREDIT,
				BigDecimal.ZERO, "USD", 1, TransactionStatus.PENDING, 0, now, now);

		transactionApiService.process(invalid);

		Transaction stored = transactions.findById(transactionId).orElseThrow();
		assertEquals(TransactionStatus.FAILED, stored.getStatus());
		assertEquals("amount must be greater than zero", stored.getFailureReason());
		assertEquals(new BigDecimal("100.0000"), accounts.findById(accountId).orElseThrow().getBalance());
		assertThrows(ConflictException.class, () -> transactionApiService.retryFailed(transactionId));
	}

	@Test
	void acceptsTransactionsAsReceivedAndRecoversSequenceFromPersistedRows() {
		String suffix = UUID.randomUUID().toString();
		String accountId = "account-" + suffix;
		accounts.save(new Account(accountId, "USD", new BigDecimal("100.00")));
		Transaction second = transaction("second-" + suffix, "request-second-" + suffix,
				accountId, 2, "20.00");
		transactionApiService.acceptBatch(List.of(second));

		assertEquals(TransactionStatus.RECEIVED,
				transactions.findById(second.getTransactionId()).orElseThrow().getStatus());
		SequenceProcessingResult pending = transactionApiService.process(second);
		assertEquals(SequenceProcessingResult.Status.BUFFERED, pending.status());
		assertEquals(List.of(1L), pending.missingSequences().stream()
				.flatMap(gap -> java.util.stream.LongStream.rangeClosed(
						gap.firstMissingSequence(), gap.lastMissingSequence()).boxed())
				.toList());

		Transaction first = transaction("first-" + suffix, "request-first-" + suffix,
				accountId, 1, "10.00");
		SequenceProcessingResult result = transactionApiService.process(first);

		assertEquals(List.of(1L, 2L), result.executions().stream()
				.map(execution -> execution.sequenceNumber()).toList());
		assertEquals(TransactionStatus.PROCESSED,
				transactions.findById(first.getTransactionId()).orElseThrow().getStatus());
		assertEquals(TransactionStatus.PROCESSED,
				transactions.findById(second.getTransactionId()).orElseThrow().getStatus());
		assertEquals(new BigDecimal("130.0000"), accounts.findById(accountId).orElseThrow().getBalance());
	}

	@Test
	void rejectsAReusedTransactionIdWithDifferentPayload() {
		String suffix = UUID.randomUUID().toString();
		String accountId = "account-" + suffix;
		String transactionId = "conflict-" + suffix;
		accounts.save(new Account(accountId, "USD", new BigDecimal("100.00")));
		Transaction original = transaction(transactionId, "request-" + suffix, accountId, 1, "10.00");
		transactionApiService.acceptBatch(List.of(original));

		Transaction changed = transaction(transactionId, "request-changed-" + suffix,
				accountId, 1, "11.00");

		assertThrows(ConflictException.class, () -> transactionApiService.acceptBatch(List.of(changed)));
		assertEquals(new BigDecimal("10.0000"),
				transactions.findById(transactionId).orElseThrow().getAmount());
	}

	@Test
	void persistsConflictingSequenceSubmissionWithoutProcessingIt() {
		String suffix = UUID.randomUUID().toString();
		String accountId = "account-" + suffix;
		accounts.save(new Account(accountId, "USD", new BigDecimal("100.00")));
		Transaction first = transaction("first-slot-" + suffix, "request-first-slot-" + suffix,
				accountId, 1, "10.00");
		Transaction conflict = transaction("conflict-slot-" + suffix, "request-conflict-slot-" + suffix,
				accountId, 1, "20.00");
		conflict.setCreatedAt(first.getCreatedAt().plusSeconds(1));
		conflict.setUpdatedAt(conflict.getCreatedAt());
		transactionApiService.acceptBatch(List.of(first, conflict));

		assertEquals(SequenceProcessingResult.Status.PROCESSED, transactionApiService.process(first).status());
		assertEquals(SequenceProcessingResult.Status.CONFLICT, transactionApiService.process(conflict).status());
		assertEquals(TransactionStatus.FAILED,
				transactions.findById(conflict.getTransactionId()).orElseThrow().getStatus());
		assertEquals(new BigDecimal("110.0000"), accounts.findById(accountId).orElseThrow().getBalance());
	}

	@Test
	void concurrentDuplicateSubmissionsCommitTheBalanceChangeOnce() throws Exception {
		String suffix = UUID.randomUUID().toString();
		String accountId = "account-" + suffix;
		String transactionId = "concurrent-" + suffix;
		accounts.save(new Account(accountId, "USD", new BigDecimal("100.00")));
		ExecutorService executor = Executors.newFixedThreadPool(2);
		CountDownLatch ready = new CountDownLatch(2);
		CountDownLatch start = new CountDownLatch(1);
		try {
			List<Future<SequenceProcessingResult>> futures = new ArrayList<>();
			for (int index = 0; index < 2; index++) {
				String requestId = "request-concurrent-" + suffix + "-" + index;
				futures.add(executor.submit(() -> {
					ready.countDown();
					if (!start.await(5, TimeUnit.SECONDS)) {
						throw new IllegalStateException("Timed out waiting to start concurrent submissions");
					}
					return transactionApiService.process(
							transaction(transactionId, requestId, accountId, 1, "10.00"));
				}));
			}
			if (!ready.await(5, TimeUnit.SECONDS)) {
				throw new IllegalStateException("Concurrent submissions did not become ready");
			}
			start.countDown();
			List<SequenceProcessingResult.Status> results = new ArrayList<>();
			for (Future<SequenceProcessingResult> future : futures) {
				results.add(future.get(10, TimeUnit.SECONDS).status());
			}

			assertEquals(1, results.stream().filter(status ->
					status == SequenceProcessingResult.Status.PROCESSED).count());
			assertEquals(1, results.stream().filter(status ->
					status == SequenceProcessingResult.Status.DUPLICATE).count());
			assertEquals(new BigDecimal("110.0000"),
					accounts.findById(accountId).orElseThrow().getBalance());
			assertEquals(TransactionStatus.PROCESSED,
					transactions.findById(transactionId).orElseThrow().getStatus());
		} finally {
			executor.shutdownNow();
		}
	}

	@Test
	void persistsRetryCountAndSuccessfulRetryWithoutRepeatingBalanceEffects() {
		String suffix = UUID.randomUUID().toString();
		String accountId = "account-" + suffix;
		accounts.save(new Account(accountId, "USD", new BigDecimal("100.00")));
		Transaction transientTransaction = transaction(
				"TXN-2005", "request-" + suffix, accountId, 1, "10.00");

		SequenceProcessingResult first = transactionApiService.process(transientTransaction);
		Transaction replay = transaction("TXN-2005", "request-replay-" + suffix,
				accountId, 1, "10.00");
		SequenceProcessingResult duplicate = transactionApiService.process(replay);

		Transaction stored = transactions.findById("TXN-2005").orElseThrow();
		assertEquals(SequenceProcessingResult.Status.PROCESSED, first.status());
		assertEquals(1, first.executions().get(0).executionResult().retryCount());
		assertEquals(SequenceProcessingResult.Status.DUPLICATE, duplicate.status());
		assertEquals(TransactionStatus.PROCESSED, stored.getStatus());
		assertEquals(1, stored.getRetryCount());
		assertEquals(new BigDecimal("110.0000"), accounts.findById(accountId).orElseThrow().getBalance());
	}

	@Test
	void persistsNegativeAmountAsFailedAndDoesNotChangeBalance() {
		String suffix = UUID.randomUUID().toString();
		String accountId = "account-" + suffix;
		String transactionId = "negative-" + suffix;
		accounts.save(new Account(accountId, "USD", new BigDecimal("100.00")));

		SequenceProcessingResult result = transactionApiService.process(transaction(
				transactionId, "request-" + suffix, accountId, 1, "-10.00"));

		Transaction stored = transactions.findById(transactionId).orElseThrow();
		assertEquals(SequenceProcessingResult.Status.INVALID, result.status());
		assertEquals(TransactionStatus.FAILED, stored.getStatus());
		assertEquals("amount must be greater than zero", stored.getFailureReason());
		assertEquals(new BigDecimal("100.0000"), accounts.findById(accountId).orElseThrow().getBalance());
	}

	@Test
	void retrievesPersistedTransactionAndSummaryThroughApiService() throws Exception {
		String suffix = UUID.randomUUID().toString();
		String accountId = "account-" + suffix;
		String transactionId = "query-" + suffix;
		accounts.save(new Account(accountId, "USD", new BigDecimal("100.00")));
		long processedBefore = transactionApiService.summarize().countsByStatus()
				.get(TransactionStatus.PROCESSED);
		long totalBefore = transactionApiService.summarize().total();
		transactionApiService.process(transaction(
				transactionId, "request-" + suffix, accountId, 1, "15.00"));

		mockMvc.perform(get("/api/transactions/{transactionId}", transactionId))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.transactionId").value(transactionId))
				.andExpect(jsonPath("$.status").value("PROCESSED"))
				.andExpect(jsonPath("$.retryCount").value(0));
		mockMvc.perform(get("/api/transactions/summary"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.countsByStatus.PROCESSED").value(processedBefore + 1))
				.andExpect(jsonPath("$.total").value(totalBefore + 1));

		assertEquals(TransactionStatus.PROCESSED,
				transactionApiService.findTransaction(transactionId).status());
	}

	@Test
	void databasePrimaryKeyRejectsDuplicateBusinessTransactionId() {
		String suffix = UUID.randomUUID().toString();
		String accountId = "account-" + suffix;
		String transactionId = "unique-" + suffix;
		accounts.save(new Account(accountId, "USD", new BigDecimal("100.00")));
		transactionApiService.process(transaction(
				transactionId, "request-" + suffix, accountId, 1, "10.00"));

		assertThrows(DataIntegrityViolationException.class, () -> jdbcTemplate.update(
				"insert into transactions (transaction_id, account_id, sequence_number, retry_count) "
						+ "values (?, ?, ?, ?)",
				transactionId, accountId, 2L, 0));
	}

	private static Transaction transaction(
			String transactionId, String requestId, String accountId, long sequence, String amount) {
		Instant now = Instant.now();
		return new Transaction(
				transactionId, requestId, accountId, TransactionType.CREDIT, new BigDecimal(amount),
				"USD", sequence, TransactionStatus.PENDING, 0, now, now);
	}
}
