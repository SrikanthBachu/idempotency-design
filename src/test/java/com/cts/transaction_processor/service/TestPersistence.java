package com.cts.transaction_processor.service;

import java.util.ArrayList;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.math.BigDecimal;

import com.cts.transaction_processor.model.Account;
import com.cts.transaction_processor.model.IdempotencyRecord;
import com.cts.transaction_processor.model.RequestIdempotencyRecord;
import com.cts.transaction_processor.model.Transaction;
import com.cts.transaction_processor.repository.AccountRepository;
import com.cts.transaction_processor.repository.IdempotencyRepository;
import com.cts.transaction_processor.repository.RequestIdempotencyRecordJpaRepository;
import com.cts.transaction_processor.repository.TransactionRepository;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

final class TestPersistence {

	private TestPersistence() {
	}

	static Context create() {
		AccountRepository accountStore = mock(AccountRepository.class);
		TransactionRepository transactionStore = mock(TransactionRepository.class);
		IdempotencyRepository idempotencyStore = mock(IdempotencyRepository.class);
		RequestIdempotencyRecordJpaRepository requestStore =
				mock(RequestIdempotencyRecordJpaRepository.class);

		Map<String, Account> accounts = new ConcurrentHashMap<>();
		Map<String, Transaction> transactions = new ConcurrentHashMap<>();
		Map<String, IdempotencyRecord> idempotencyRecords = new ConcurrentHashMap<>();
		Map<String, RequestIdempotencyRecord> requestRecords = new ConcurrentHashMap<>();

		when(accountStore.findById(anyString())).thenAnswer(call ->
				Optional.ofNullable(accounts.get(call.getArgument(0))));
		when(accountStore.findByIdForUpdate(anyString())).thenAnswer(call ->
				Optional.ofNullable(accounts.get(call.getArgument(0))));
		when(accountStore.existsById(anyString())).thenAnswer(call ->
				accounts.containsKey(call.getArgument(0)));
		when(accountStore.save(any(Account.class))).thenAnswer(call -> {
			Account account = call.getArgument(0);
			accounts.put(account.getAccountId(), account);
			return account;
		});

		when(transactionStore.findById(anyString())).thenAnswer(call ->
				Optional.ofNullable(transactions.get(call.getArgument(0))));
		when(transactionStore.findByRequestId(anyString())).thenAnswer(call ->
				transactions.values().stream()
						.filter(transaction -> call.getArgument(0).equals(transaction.getRequestId()))
						.findFirst());
		when(transactionStore.findByAccountId(anyString())).thenAnswer(call ->
				transactions.values().stream()
						.filter(transaction -> call.getArgument(0).equals(transaction.getAccountId()))
						.toList());
		when(transactionStore.findByAccountIdAndSequenceNumberOrderByCreatedAtAscTransactionIdAsc(
				anyString(), org.mockito.ArgumentMatchers.anyLong()))
				.thenAnswer(call -> transactions.values().stream()
						.filter(transaction -> call.getArgument(0).equals(transaction.getAccountId())
								&& call.<Long>getArgument(1) == transaction.getSequenceNumber())
						.sorted((first, second) -> {
							int createdOrder = first.getCreatedAt().compareTo(second.getCreatedAt());
							return createdOrder != 0 ? createdOrder
									: first.getTransactionId().compareTo(second.getTransactionId());
						})
						.toList());
		when(transactionStore.existsById(anyString())).thenAnswer(call ->
				transactions.containsKey(call.getArgument(0)));
		when(transactionStore.existsByTransactionId(anyString())).thenAnswer(call ->
				transactions.containsKey(call.getArgument(0)));
		when(transactionStore.findAll()).thenAnswer(call -> new ArrayList<>(transactions.values()));
		when(transactionStore.save(any(Transaction.class))).thenAnswer(call -> {
			Transaction transaction = call.getArgument(0);
			transactions.put(transaction.getTransactionId(), transaction);
			return transaction;
		});
		when(transactionStore.saveAndFlush(any(Transaction.class))).thenAnswer(call -> {
			Transaction transaction = call.getArgument(0);
			transactions.put(transaction.getTransactionId(), transaction);
			return transaction;
		});

		when(idempotencyStore.findById(anyString())).thenAnswer(call ->
				Optional.ofNullable(idempotencyRecords.get(call.getArgument(0))));
		when(idempotencyStore.findByReversedTransactionId(anyString())).thenAnswer(call ->
				idempotencyRecords.values().stream()
						.filter(record -> call.getArgument(0).equals(record.getReversedTransactionId()))
						.findFirst());
		when(idempotencyStore.existsById(anyString())).thenAnswer(call ->
				idempotencyRecords.containsKey(call.getArgument(0)));
		when(idempotencyStore.save(any(IdempotencyRecord.class))).thenAnswer(call -> {
			IdempotencyRecord record = call.getArgument(0);
			idempotencyRecords.put(record.getTransactionId(), record);
			return record;
		});

		when(requestStore.findById(anyString())).thenAnswer(call ->
				Optional.ofNullable(requestRecords.get(call.getArgument(0))));
		when(requestStore.existsById(anyString())).thenAnswer(call ->
				requestRecords.containsKey(call.getArgument(0)));
		when(requestStore.save(any(RequestIdempotencyRecord.class))).thenAnswer(call -> {
			RequestIdempotencyRecord record = call.getArgument(0);
			requestRecords.put(record.getRequestId(), record);
			return record;
		});

		return new Context(accountStore, transactionStore, idempotencyStore, requestStore);
	}

	static void saveAccount(AccountRepository repository, String accountId, BigDecimal balance) {
		repository.save(new Account(accountId, "USD", balance));
	}

	static BigDecimal balance(AccountRepository repository, String accountId) {
		return repository.findById(accountId).orElseThrow().getBalance();
	}

	record Context(
			AccountRepository accounts,
			TransactionRepository transactions,
			IdempotencyRepository idempotency,
			RequestIdempotencyRecordJpaRepository requests) {
	}
}
