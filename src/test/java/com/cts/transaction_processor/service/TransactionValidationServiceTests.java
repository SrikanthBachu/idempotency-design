package com.cts.transaction_processor.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.cts.transaction_processor.model.Transaction;
import com.cts.transaction_processor.model.TransactionStatus;
import com.cts.transaction_processor.model.TransactionType;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;

class TransactionValidationServiceTests {

	private static ValidatorFactory validatorFactory;
	private TransactionValidationService validationService;

	@BeforeAll
	static void createValidatorFactory() {
		validatorFactory = Validation.buildDefaultValidatorFactory();
	}

	@AfterAll
	static void closeValidatorFactory() {
		validatorFactory.close();
	}

	@BeforeEach
	void createValidationService() {
		Validator validator = validatorFactory.getValidator();
		validationService = new TransactionValidationService(validator);
	}

	@Test
	void acceptsValidTransactionRequest() {
		TransactionValidationResult result = validationService.validate(validTransaction());

		assertTrue(result.isValid());
		assertTrue(result.errors().isEmpty());
	}

	@Test
	void reportsAllInvalidRequestFields() {
		Transaction transaction = new Transaction(
				" ", "", null, null, BigDecimal.ZERO, "usd", 0,
				null, 0, null, null);

		TransactionValidationResult result = validationService.validate(transaction);

		assertFalse(result.isValid());
		assertEquals(List.of(
				"accountId must not be blank",
				"amount must be greater than zero",
				"currency must be a 3-letter uppercase currency code",
				"requestId must not be blank",
				"sequenceNumber must be greater than zero",
				"transactionId must not be blank",
				"transactionType must be a supported transaction type"), result.errors());
	}

	@Test
	void rejectsUnknownIsoCurrencyCodes() {
		Transaction transaction = validTransaction();
		transaction.setCurrency("ZZZ");

		TransactionValidationResult result = validationService.validate(transaction);

		assertEquals(List.of("currency must be a valid ISO 4217 currency code"), result.errors());
	}

	@Test
	void validatesEachBatchRecordWithoutStoppingAtInvalidRecords() {
		Transaction invalidTransaction = validTransaction();
		invalidTransaction.setTransactionId("");
		List<TransactionValidationResult> results = validationService.validateBatch(
				Arrays.asList(validTransaction(), invalidTransaction, null, validTransaction()));

		assertEquals(4, results.size());
		assertTrue(results.get(0).isValid());
		assertEquals(List.of("transactionId must not be blank"), results.get(1).errors());
		assertEquals(List.of("transaction must not be null"), results.get(2).errors());
		assertTrue(results.get(3).isValid());
	}

	private static Transaction validTransaction() {
		Instant now = Instant.parse("2026-01-01T00:00:00Z");
		return new Transaction(
				"txn-1", "req-1", "acct-1", TransactionType.CREDIT, new BigDecimal("10.00"),
				"USD", 1, TransactionStatus.PENDING, 0, now, now);
	}
}
