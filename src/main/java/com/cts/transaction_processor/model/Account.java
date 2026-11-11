package com.cts.transaction_processor.model;

import java.math.BigDecimal;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "accounts")
public class Account {

	@Id
	@Column(name = "account_id", nullable = false, length = 100)
	private String accountId;

	@Column(nullable = false, length = 3)
	private String currency;

	@Column(nullable = false, precision = 19, scale = 4)
	private BigDecimal balance;

	protected Account() {
	}

	public Account(String accountId, String currency, BigDecimal balance) {
		this.accountId = accountId;
		this.currency = currency;
		this.balance = balance;
	}

	public String getAccountId() {
		return accountId;
	}

	public String getCurrency() {
		return currency;
	}

	public void setCurrency(String currency) {
		this.currency = currency;
	}

	public BigDecimal getBalance() {
		return balance;
	}

	public void setBalance(BigDecimal balance) {
		this.balance = balance;
	}
}
