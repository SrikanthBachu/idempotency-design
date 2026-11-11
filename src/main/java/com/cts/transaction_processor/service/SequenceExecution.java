package com.cts.transaction_processor.service;

import java.util.List;

public record SequenceExecution(
		long sequenceNumber,
		String transactionId,
		boolean terminalInvalid,
		IdempotencyResult executionResult,
		List<String> errors) {

	public SequenceExecution {
		errors = List.copyOf(errors);
	}
}
