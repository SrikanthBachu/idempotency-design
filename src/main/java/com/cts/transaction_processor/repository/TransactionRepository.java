package com.cts.transaction_processor.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.cts.transaction_processor.model.Transaction;
import com.cts.transaction_processor.model.TransactionStatus;

public interface TransactionRepository extends JpaRepository<Transaction, String> {

	java.util.Optional<Transaction> findByTransactionId(String transactionId);

	List<Transaction> findByAccountId(String accountId);

	List<Transaction> findByAccountIdAndSequenceNumberOrderByCreatedAtAscTransactionIdAsc(
			String accountId, long sequenceNumber);

	java.util.Optional<Transaction> findByRequestId(String requestId);

	boolean existsByTransactionId(String transactionId);

	List<Transaction> findByStatus(TransactionStatus status);

	@Query("select transaction.status as status, count(transaction) as transactionCount "
			+ "from Transaction transaction group by transaction.status")
	List<StatusCount> countGroupedByStatus();

	interface StatusCount {
		TransactionStatus getStatus();

		long getTransactionCount();
	}
}
