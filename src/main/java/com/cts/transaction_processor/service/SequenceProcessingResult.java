package com.cts.transaction_processor.service;

import java.util.List;

public record SequenceProcessingResult(
		String accountId,
		long submittedSequence,
		Status status,
		String message,
		long nextExpectedSequence,
		List<SequenceGap> missingSequences,
		List<Long> pendingSequences,
		List<SequenceExecution> executions,
		List<String> errors) {

	public SequenceProcessingResult {
		missingSequences = List.copyOf(missingSequences);
		pendingSequences = List.copyOf(pendingSequences);
		executions = List.copyOf(executions);
		errors = List.copyOf(errors);
	}

	public enum Status {
		PROCESSED,
		BUFFERED,
		RECOVERY,
		INVALID,
		DUPLICATE,
		CONFLICT,
		FAILED,
		REJECTED,
		UNSEQUENCABLE
	}
}
