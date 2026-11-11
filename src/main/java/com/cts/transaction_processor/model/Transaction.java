package com.cts.transaction_processor.model;

import java.math.BigDecimal;
import java.time.Instant;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;

@Entity
@Table(name = "transactions", indexes = {
		@Index(name = "idx_transactions_account_sequence", columnList = "account_id, sequence_number")
}, uniqueConstraints = {
		@UniqueConstraint(name = "uk_transactions_request_id", columnNames = "request_id")
})
public class Transaction {

	@Id
	@Column(name = "transaction_id", nullable = false, length = 100)
	@NotBlank(message = "must not be blank")
	private String transactionId;

	@Column(name = "request_id", length = 100, nullable = true)
	@NotBlank(message = "must not be blank")
	private String requestId;

	@Column(name = "account_id", length = 100, nullable = true)
	@NotBlank(message = "must not be blank")
	private String accountId;

	@Column(name = "original_transaction_id", length = 100)
	private String originalTransactionId;

	@Enumerated(EnumType.STRING)
	@Column(name = "transaction_type", length = 20, nullable = true)
	@NotNull(message = "must be a supported transaction type")
	private TransactionType transactionType;

	@Column(precision = 19, scale = 4, nullable = true)
	@NotNull(message = "must not be null")
	@Positive(message = "must be greater than zero")
	private BigDecimal amount;

	@Column(length = 3, nullable = true)
	@NotBlank(message = "must not be blank")
	@Pattern(regexp = "[A-Z]{3}", message = "must be a 3-letter uppercase currency code")
	private String currency;

	@Column(name = "sequence_number", nullable = false)
	@Positive(message = "must be greater than zero")
	private long sequenceNumber;

	@Enumerated(EnumType.STRING)
	@Column(length = 20, nullable = true)
	@NotNull
	private TransactionStatus status;

	@Column(name = "retry_count", nullable = false)
	@PositiveOrZero
	private int retryCount;

	@Column(name = "created_at", nullable = true)
	@NotNull
	private Instant createdAt;

	@Column(name = "updated_at", nullable = true)
	@NotNull
	private Instant updatedAt;

	@Column(name = "failure_reason", length = 2000)
	private String failureReason;

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

	public String getFailureReason() {
		return failureReason;
	}

	public void setFailureReason(String failureReason) {
		this.failureReason = failureReason;
	}

	@PrePersist
	private void initializeTimestamps() {
		Instant now = Instant.now();
		if (createdAt == null) {
			createdAt = now;
		}
		updatedAt = now;
	}

	@PreUpdate
	private void updateTimestamp() {
		updatedAt = Instant.now();
	}
}
