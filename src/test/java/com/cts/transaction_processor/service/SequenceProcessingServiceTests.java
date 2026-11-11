package com.cts.transaction_processor.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

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

class SequenceProcessingServiceTests {

	private static ValidatorFactory validatorFactory;
	private AccountRepository accountRepository;
	private SequenceProcessingService sequenceProcessingService;

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
		TestPersistence.Context persistence = TestPersistence.create();
		accountRepository = persistence.accounts();
		TestPersistence.saveAccount(accountRepository, "account-1", new BigDecimal("100.00"));
		TransactionValidationService validationService =
				new TransactionValidationService(validatorFactory.getValidator());
		IdempotencyService idempotencyService = new IdempotencyService(
				persistence.idempotency(), persistence.requests(), accountRepository,
				persistence.transactions(), validationService,
				(transaction, retryCount) -> null);
		TransactionRetryService executionService = new TransactionRetryService(idempotencyService);
		sequenceProcessingService = new SequenceProcessingService(
				validationService, executionService, persistence.transactions());
	}

	@Test
	void buffersSequenceTwoAndProcessesItAfterSequenceOneArrives() {
		SequenceProcessingResult sequence2 = sequenceProcessingService.submit(
				transaction(2, "txn-2", "40.00", TransactionType.DEBIT));

		assertEquals(SequenceProcessingResult.Status.BUFFERED, sequence2.status());
		assertEquals(List.of(2L), sequence2.pendingSequences());
		assertEquals(List.of(new SequenceGap(1, 1)), sequence2.missingSequences());
		assertTrue(sequence2.executions().isEmpty());
		assertEquals(new BigDecimal("100.00"), TestPersistence.balance(accountRepository, "account-1"));

		SequenceProcessingResult sequence1 = sequenceProcessingService.submit(
				transaction(1, "txn-1", "100.00", TransactionType.CREDIT));

		assertEquals(SequenceProcessingResult.Status.PROCESSED, sequence1.status());
		assertEquals(List.of(1L, 2L), sequence1.executions().stream()
				.map(SequenceExecution::sequenceNumber).toList());
		assertEquals(new BigDecimal("200.00"), sequence1.executions().get(0).executionResult().balance());
		assertEquals(new BigDecimal("160.00"), sequence1.executions().get(1).executionResult().balance());
		assertTrue(sequence1.pendingSequences().isEmpty());
		assertTrue(sequence1.missingSequences().isEmpty());
		assertEquals(new BigDecimal("160.00"), TestPersistence.balance(accountRepository, "account-1"));
	}

	@Test
	void buffersFutureSequenceAndAdvancesPastTerminalInvalidInput() {
		SequenceProcessingResult sequence3 = sequenceProcessingService.submit(transaction(3, "txn-3", "30.00"));

		assertEquals(SequenceProcessingResult.Status.BUFFERED, sequence3.status());
		assertEquals(List.of(new SequenceGap(1, 2)), sequence3.missingSequences());
		assertEquals(new BigDecimal("100.00"), TestPersistence.balance(accountRepository, "account-1"));

		SequenceProcessingResult sequence1 = sequenceProcessingService.submit(transaction(1, "txn-1", "10.00"));

		assertEquals(SequenceProcessingResult.Status.PROCESSED, sequence1.status());
		assertEquals(2, sequence1.nextExpectedSequence());
		assertEquals(List.of(new SequenceGap(2, 2)), sequence1.missingSequences());
		assertEquals(List.of(3L), sequence1.pendingSequences());

		SequenceProcessingResult sequence2 = sequenceProcessingService.submit(transaction(2, "txn-2", "0"));

		assertEquals(SequenceProcessingResult.Status.INVALID, sequence2.status());
		assertEquals(4, sequence2.nextExpectedSequence());
		assertTrue(sequence2.missingSequences().isEmpty());
		assertTrue(sequence2.pendingSequences().isEmpty());
		assertEquals(List.of(2L, 3L), sequence2.executions().stream()
				.map(SequenceExecution::sequenceNumber).toList());
		assertTrue(sequence2.executions().get(0).terminalInvalid());
		assertFalse(sequence2.executions().get(1).terminalInvalid());
		assertEquals(new BigDecimal("140.00"), TestPersistence.balance(accountRepository, "account-1"));
	}

	@Test
	void duplicateSequenceDoesNotExecuteBalanceChangeTwice() {
		Transaction transaction = transaction(1, "txn-1", "10.00");

		SequenceProcessingResult first = sequenceProcessingService.submit(transaction);
		SequenceProcessingResult duplicate = sequenceProcessingService.submit(transaction);

		assertEquals(SequenceProcessingResult.Status.PROCESSED, first.status());
		assertEquals(SequenceProcessingResult.Status.DUPLICATE, duplicate.status());
		assertEquals(new BigDecimal("110.00"), TestPersistence.balance(accountRepository, "account-1"));
	}

	@Test
	void conflictingPayloadForTheSamePendingSequenceIsRejected() {
		sequenceProcessingService.submit(transaction(2, "txn-2", "10.00"));

		SequenceProcessingResult conflict =
				sequenceProcessingService.submit(transaction(2, "txn-2", "11.00"));

		assertEquals(SequenceProcessingResult.Status.CONFLICT, conflict.status());
		assertEquals(List.of(2L), conflict.pendingSequences());
	}

	@Test
	void exposesBufferedRecordsForRecoveryWithoutProcessingAcrossTheGap() {
		sequenceProcessingService.submit(transaction(3, "txn-3", "30.00"));

		List<Transaction> pending = sequenceProcessingService.getPendingTransactions("account-1");
		SequenceProcessingResult recovery = sequenceProcessingService.recoverPending("account-1");

		assertEquals(1, pending.size());
		assertEquals(3, pending.get(0).getSequenceNumber());
		assertTrue(recovery.executions().isEmpty());
		assertEquals(List.of(new SequenceGap(1, 2)), recovery.missingSequences());
		assertEquals(new BigDecimal("100.00"), TestPersistence.balance(accountRepository, "account-1"));
	}

	private static Transaction transaction(long sequence, String transactionId, String amount) {
		return transaction(sequence, transactionId, amount, TransactionType.CREDIT);
	}

	private static Transaction transaction(
			long sequence, String transactionId, String amount, TransactionType transactionType) {
		Instant now = Instant.parse("2026-01-01T00:00:00Z");
		return new Transaction(
				transactionId, "request-" + transactionId, "account-1", transactionType,
				new BigDecimal(amount), "USD", sequence, TransactionStatus.PENDING, 0, now, now);
	}
}
