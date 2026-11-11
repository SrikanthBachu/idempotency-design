package com.cts.transaction_processor.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
import com.cts.transaction_processor.repository.TransactionResultRepository;

import jakarta.validation.Validation;
import jakarta.validation.ValidatorFactory;

class TransactionProcessorTests {

	private static ValidatorFactory validatorFactory;
	private AccountRepository accounts;
	private TransactionRepository transactions;
	private TransactionResultRepository results;
	private TransactionProcessor processor;

	@BeforeAll
	static void createValidatorFactory() {
		validatorFactory = Validation.buildDefaultValidatorFactory();
	}

	@AfterAll
	static void closeValidatorFactory() {
		validatorFactory.close();
	}

	@BeforeEach
	void createProcessor() {
		TestPersistence.Context persistence = TestPersistence.create();
		accounts = persistence.accounts();
		TestPersistence.saveAccount(accounts, "account-1", new BigDecimal("100.00"));
		transactions = persistence.transactions();
		results = new TransactionResultRepository();
		TransactionValidationService validation = new TransactionValidationService(validatorFactory.getValidator());
		IdempotencyService idempotency = new IdempotencyService(
				persistence.idempotency(), persistence.requests(), accounts, transactions,
				validation, new TransactionFailureSimulator());
		SequenceProcessingService sequencing = new SequenceProcessingService(
				validation, new TransactionRetryService(idempotency), transactions);
		processor = new TransactionProcessor(sequencing, results);
	}

	@Test
	void coordinatesValidationSequenceExecutionAndResultPersistence() {
		SequenceProcessingResult sequence3 = processor.process(credit(3, "txn-3", "30.00"));
		SequenceProcessingResult sequence1 = processor.process(credit(1, "txn-1", "10.00"));
		SequenceProcessingResult sequence2 = processor.process(credit(2, "txn-2", "0.00"));

		assertEquals(SequenceProcessingResult.Status.BUFFERED, sequence3.status());
		assertEquals(SequenceProcessingResult.Status.PROCESSED, sequence1.status());
		assertEquals(SequenceProcessingResult.Status.INVALID, sequence2.status());
		assertEquals(new BigDecimal("140.00"), TestPersistence.balance(accounts, "account-1"));
		assertEquals(TransactionStatus.PROCESSED, transactions.findById("txn-1").orElseThrow().getStatus());
		assertEquals(TransactionStatus.PROCESSED, transactions.findById("txn-3").orElseThrow().getStatus());
		assertTrue(processor.findResultByRequestId("request-txn-2").isPresent());
		assertEquals(List.of(1L, 2L, 3L), List.of(
				processor.findExecution("account-1", 1).orElseThrow().sequenceNumber(),
				processor.findExecution("account-1", 2).orElseThrow().sequenceNumber(),
				processor.findExecution("account-1", 3).orElseThrow().sequenceNumber()));
	}

	@Test
	void processesCreditDebitAndReversalWithIdempotentBalanceChanges() {
		Transaction credit = credit(1, "credit-1", "10.00");
		SequenceProcessingResult creditResult = processor.process(credit);

		Transaction debit = new Transaction(
				"debit-2", "request-debit-2", "account-1", TransactionType.DEBIT,
				new BigDecimal("5.00"), "USD", 2, TransactionStatus.PENDING, 0,
				now(), now());
		processor.process(debit);
		Transaction reversal = new Transaction(
				"reversal-3", "request-reversal-3", "account-1", "credit-1", TransactionType.REVERSAL,
				new BigDecimal("10.00"), "USD", 3, TransactionStatus.PENDING, 0,
				now(), now());
		processor.process(reversal);
		SequenceProcessingResult duplicate = processor.process(credit);

		assertEquals(SequenceProcessingResult.Status.PROCESSED, creditResult.status());
		assertEquals(SequenceProcessingResult.Status.DUPLICATE, duplicate.status());
		assertEquals(new BigDecimal("95.00"), TestPersistence.balance(accounts, "account-1"));
	}

	@Test
	void batchContinuesAfterInvalidRecordAndPersistsEachOutcome() {
		Transaction invalid = credit(2, "txn-2", "-1.00");

		List<SequenceProcessingResult> results = processor.processBatch(List.of(
				credit(1, "txn-1", "10.00"),
				invalid,
				credit(3, "txn-3", "5.00")));

		assertEquals(3, results.size());
		assertEquals(SequenceProcessingResult.Status.PROCESSED, results.get(0).status());
		assertEquals(SequenceProcessingResult.Status.INVALID, results.get(1).status());
		assertEquals(SequenceProcessingResult.Status.PROCESSED, results.get(2).status());
		assertEquals(new BigDecimal("115.00"), TestPersistence.balance(accounts, "account-1"));
		assertEquals(3, this.results.findAll().size());
	}

	@Test
	void transientFailureIsRetriedAndFinalStateIsPersisted() {
		Transaction special = credit(1, "TXN-2005", "10.00");

		SequenceProcessingResult result = processor.process(special);

		assertEquals(SequenceProcessingResult.Status.PROCESSED, result.status());
		assertEquals(1, transactions.findById("TXN-2005").orElseThrow().getRetryCount());
		assertEquals(TransactionStatus.PROCESSED, transactions.findById("TXN-2005").orElseThrow().getStatus());
		assertEquals(new BigDecimal("110.00"), TestPersistence.balance(accounts, "account-1"));
	}

	private static Transaction credit(long sequence, String transactionId, String amount) {
		return new Transaction(
				transactionId, "request-" + transactionId, "account-1", TransactionType.CREDIT,
				new BigDecimal(amount), "USD", sequence, TransactionStatus.PENDING, 0, now(), now());
	}

	private static Instant now() {
		return Instant.parse("2026-01-01T00:00:00Z");
	}
}
