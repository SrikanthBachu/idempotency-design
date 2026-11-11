package com.cts.transaction_processor.model;

import java.math.BigDecimal;
import java.time.Instant;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;

public class Transaction {

	@NotBlank(message = "must not be blank")
	private String transactionId;

	@NotBlank(message = "must not be blank")
	private String requestId;

	@NotBlank(message = "must not be blank")
	private String accountId;

	private String originalTransactionId;

	@NotNull(message = "must be a supported transaction type")
	private TransactionType transactionType;

	@NotNull(message = "must not be null")
	@Positive(message = "must be greater than zero")
	private BigDecimal amount;

	@NotBlank(message = "must not be blank")
	@Pattern(regexp = "[A-Z]{3}", message = "must be a 3-letter uppercase currency code")
	private String currency;

	@Positive(message = "must be greater than zero")
	private long sequenceNumber;

	@NotNull
	private TransactionStatus status;

	@PositiveOrZero
	private int retryCount;

	@NotNull
	private Instant createdAt;

	@NotNull
	private Instant updatedAt;

	public Transaction() {
	}

	public Transaction(String transactionId, String requestId, String accountId,
			TransactionType transactionType, BigDecimal amount, String currency,
			long sequenceNumber, TransactionStatus status, int retryCount,
			Instant createdAt, Instant updatedAt) {
		this(transactionId, requestId, accountId, null, transactionType, amount, currency,
				sequenceNumber, status, retryCount, createdAt, updatedAt);
	}

	public Transaction(String transactionId, String requestId, String accountId,
			String originalTransactionId, TransactionType transactionType, BigDecimal amount,
			String currency, long sequenceNumber, TransactionStatus status, int retryCount,
			Instant createdAt, Instant updatedAt) {
		this.transactionId = transactionId;
		this.requestId = requestId;
		this.accountId = accountId;
		this.originalTransactionId = originalTransactionId;
		this.transactionType = transactionType;
		this.amount = amount;
		this.currency = currency;
		this.sequenceNumber = sequenceNumber;
		this.status = status;
		this.retryCount = retryCount;
		this.createdAt = createdAt;
		this.updatedAt = updatedAt;
	}

	public String getTransactionId() {
		return transactionId;
	}

	public void setTransactionId(String transactionId) {
		this.transactionId = transactionId;
	}

	public String getRequestId() {
		return requestId;
	}

	public void setRequestId(String requestId) {
		this.requestId = requestId;
	}

	public String getAccountId() {
		return accountId;
	}

	public void setAccountId(String accountId) {
		this.accountId = accountId;
	}

	public String getOriginalTransactionId() {
		return originalTransactionId;
	}

	public void setOriginalTransactionId(String originalTransactionId) {
		this.originalTransactionId = originalTransactionId;
	}

	public TransactionType getTransactionType() {
		return transactionType;
	}

	public void setTransactionType(TransactionType transactionType) {
		this.transactionType = transactionType;
	}

	public BigDecimal getAmount() {
		return amount;
	}

	public void setAmount(BigDecimal amount) {
		this.amount = amount;
	}

	public String getCurrency() {
		return currency;
	}

	public void setCurrency(String currency) {
		this.currency = currency;
	}

	public long getSequenceNumber() {
		return sequenceNumber;
	}

	public void setSequenceNumber(long sequenceNumber) {
		this.sequenceNumber = sequenceNumber;
	}

	public TransactionStatus getStatus() {
		return status;
	}

	public void setStatus(TransactionStatus status) {
		this.status = status;
	}

	public int getRetryCount() {
		return retryCount;
	}

	public void setRetryCount(int retryCount) {
		this.retryCount = retryCount;
	}

	public Instant getCreatedAt() {
		return createdAt;
	}

	public void setCreatedAt(Instant createdAt) {
		this.createdAt = createdAt;
	}

	public Instant getUpdatedAt() {
		return updatedAt;
	}

	public void setUpdatedAt(Instant updatedAt) {
		this.updatedAt = updatedAt;
	}
}
