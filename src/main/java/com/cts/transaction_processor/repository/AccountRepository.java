package com.cts.transaction_processor.repository;

import java.math.BigDecimal;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import org.springframework.stereotype.Repository;

@Repository
public class AccountRepository {

	private final ConcurrentMap<String, BigDecimal> balances = new ConcurrentHashMap<>();

	/**
	 * Inserts or replaces an account's balance.
	 */
	public BigDecimal saveBalance(String accountId, BigDecimal balance) {
		String key = requireKey(accountId, "accountId");
		BigDecimal value = Objects.requireNonNull(balance, "balance must not be null");
		balances.put(key, value);
		return value;
	}

	public Optional<BigDecimal> findBalance(String accountId) {
		return Optional.ofNullable(balances.get(requireKey(accountId, "accountId")));
	}

	/**
	 * Replaces the balance only when the account already exists.
	 */
	public BigDecimal updateBalance(String accountId, BigDecimal balance) {
		String key = requireKey(accountId, "accountId");
		BigDecimal value = Objects.requireNonNull(balance, "balance must not be null");
		BigDecimal updated = balances.computeIfPresent(key, (ignored, current) -> value);
		if (updated == null) {
			throw new IllegalStateException("Account does not exist: " + key);
		}
		return updated;
	}

	/**
	 * Atomically adds a delta to an existing account balance.
	 */
	public BigDecimal adjustBalance(String accountId, BigDecimal delta) {
		String key = requireKey(accountId, "accountId");
		BigDecimal adjustment = Objects.requireNonNull(delta, "delta must not be null");
		BigDecimal updated = balances.computeIfPresent(key, (ignored, current) -> current.add(adjustment));
		if (updated == null) {
			throw new IllegalStateException("Account does not exist: " + key);
		}
		return updated;
	}

	private static String requireKey(String key, String name) {
		Objects.requireNonNull(key, name + " must not be null");
		if (key.isBlank()) {
			throw new IllegalArgumentException(name + " must not be blank");
		}
		return key;
	}
}
