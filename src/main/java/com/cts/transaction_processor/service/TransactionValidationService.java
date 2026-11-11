package com.cts.transaction_processor.service;

import java.util.ArrayList;
import java.util.Currency;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

import org.springframework.stereotype.Service;

import com.cts.transaction_processor.model.Transaction;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;

@Service
public class TransactionValidationService {

	private static final List<String> REQUEST_FIELDS = List.of(
			"transactionId",
			"accountId",
			"requestId",
			"amount",
			"currency",
			"transactionType",
			"sequenceNumber");

	private final Validator validator;

	public TransactionValidationService(Validator validator) {
		this.validator = Objects.requireNonNull(validator, "validator must not be null");
	}

	public TransactionValidationResult validate(Transaction transaction) {
		if (transaction == null) {
			return new TransactionValidationResult(null, List.of("transaction must not be null"));
		}

		Set<String> errors = new TreeSet<>();
		for (String field : REQUEST_FIELDS) {
			Set<ConstraintViolation<Transaction>> violations = validator.validateProperty(transaction, field);
			for (ConstraintViolation<Transaction> violation : violations) {
				errors.add(field + " " + violation.getMessage());
			}
		}

		if (transaction.getTransactionType() == com.cts.transaction_processor.model.TransactionType.REVERSAL
				&& (transaction.getOriginalTransactionId() == null
						|| transaction.getOriginalTransactionId().isBlank())) {
			errors.add("originalTransactionId must not be blank for a reversal");
		} else if (transaction.getTransactionType() != com.cts.transaction_processor.model.TransactionType.REVERSAL
				&& transaction.getOriginalTransactionId() != null
				&& !transaction.getOriginalTransactionId().isBlank()) {
			errors.add("originalTransactionId is only allowed for a reversal");
		}

		validateIsoCurrency(transaction.getCurrency(), errors);
		return new TransactionValidationResult(transaction, new ArrayList<>(errors));
	}

	public List<TransactionValidationResult> validateBatch(List<Transaction> transactions) {
		Objects.requireNonNull(transactions, "transactions must not be null");
		return transactions.stream().map(this::validate).toList();
	}

	private static void validateIsoCurrency(String currency, Set<String> errors) {
		if (currency == null || !currency.matches("[A-Z]{3}")) {
			return;
		}
		try {
			Currency.getInstance(currency);
		} catch (IllegalArgumentException exception) {
			errors.add("currency must be a valid ISO 4217 currency code");
		}
	}
}
