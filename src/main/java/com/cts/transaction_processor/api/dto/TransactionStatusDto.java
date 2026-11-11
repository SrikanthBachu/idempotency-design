package com.cts.transaction_processor.api.dto;

import java.math.BigDecimal;

import com.cts.transaction_processor.model.TransactionStatus;
import com.cts.transaction_processor.model.TransactionType;

public record TransactionStatusDto(
		String transactionId,
		String accountId,
		long sequenceNumber,
		TransactionType transactionType,
		BigDecimal amount,
		String currency,
		TransactionStatus status,
		int retryCount,
		String failureReason) {
}
