package com.cts.transaction_processor.service;

import java.util.List;

import com.cts.transaction_processor.model.Transaction;

public record TransactionValidationResult(Transaction transaction, List<String> errors) {

	public TransactionValidationResult {
		errors = List.copyOf(errors);
	}

	public boolean isValid() {
		return errors.isEmpty();
	}
}
