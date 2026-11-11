package com.cts.transaction_processor.model;

import java.math.BigDecimal;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

@Entity
@Table(name = "transaction_idempotency", indexes = {
		@Index(name = "idx_idempotency_reversed_transaction", columnList = "reversed_transaction_id")
}, uniqueConstraints = {
		@UniqueConstraint(name = "uk_idempotency_reversed_transaction", columnNames = "reversed_transaction_id")
})
public class IdempotencyRecord {

	@Id
	@Column(name = "transaction_id", nullable = false, length = 100)
	private String transactionId;

	@ManyToOne(fetch = FetchType.EAGER, optional = false)
	@JoinColumn(name = "transaction_id", insertable = false, updatable = false)
	private Transaction transaction;

	@Column(name = "balance_delta", nullable = false, precision = 19, scale = 4)
	private BigDecimal balanceDelta;

	@Column(name = "balance_after", nullable = false, precision = 19, scale = 4)
	private BigDecimal balanceAfter;

	@Column(name = "reversed_transaction_id", length = 100)
	private String reversedTransactionId;

	@Column(nullable = false)
	private boolean reversed;

	protected IdempotencyRecord() {
	}

	public IdempotencyRecord(Transaction transaction, BigDecimal balanceDelta,
			BigDecimal balanceAfter, String reversedTransactionId) {
		this.transaction = transaction;
		this.transactionId = transaction.getTransactionId();
		this.balanceDelta = balanceDelta;
		this.balanceAfter = balanceAfter;
		this.reversedTransactionId = reversedTransactionId;
	}

	public String getTransactionId() {
		return transactionId;
	}

	public Transaction getTransaction() {
		return transaction;
	}

	public BigDecimal getBalanceDelta() {
		return balanceDelta;
	}

	public BigDecimal getBalanceAfter() {
		return balanceAfter;
	}

	public String getReversedTransactionId() {
		return reversedTransactionId;
	}

	public boolean isReversed() {
		return reversed;
	}

	public void setReversed(boolean reversed) {
		this.reversed = reversed;
	}
}
