package com.cts.transaction_processor.api.dto;

import java.math.BigDecimal;
import java.time.Instant;

import com.cts.transaction_processor.model.Transaction;
import com.cts.transaction_processor.model.TransactionStatus;
import com.cts.transaction_processor.model.TransactionType;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;

public record TransactionRequestDto(
		@NotBlank(message = "must not be blank") String transactionId,
		@NotBlank(message = "must not be blank") String accountId,
		@NotBlank(message = "must not be blank") String requestId,
		@NotNull(message = "must be a supported transaction type") TransactionType transactionType,
		@NotNull(message = "must not be null")
		@Positive(message = "must be greater than zero") BigDecimal amount,
		@NotBlank(message = "must not be blank")
		@Pattern(regexp = "[A-Z]{3}", message = "must be a 3-letter uppercase currency code") String currency,
		@Positive(message = "must be greater than zero") long sequenceNumber,
		String originalTransactionId) {

	public Transaction toTransaction() {
		Instant now = Instant.now();
		return new Transaction(
				transactionId,
				requestId,
				accountId,
				originalTransactionId,
				transactionType,
				amount,
				currency,
				sequenceNumber,
				TransactionStatus.PENDING,
				0,
				now,
				now);
	}
}
