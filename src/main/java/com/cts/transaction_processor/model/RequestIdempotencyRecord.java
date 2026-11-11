package com.cts.transaction_processor.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

@Entity
@Table(name = "request_idempotency")
public class RequestIdempotencyRecord {

	@Id
	@Column(name = "request_id", nullable = false, length = 100)
	private String requestId;

	@ManyToOne(fetch = FetchType.EAGER, optional = false)
	@JoinColumn(name = "transaction_id", nullable = false)
	private Transaction transaction;

	protected RequestIdempotencyRecord() {
	}

	public RequestIdempotencyRecord(String requestId, Transaction transaction) {
		this.requestId = requestId;
		this.transaction = transaction;
	}

	public String getRequestId() {
		return requestId;
	}

	public Transaction getTransaction() {
		return transaction;
	}
}
