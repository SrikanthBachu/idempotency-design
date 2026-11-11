package com.cts.transaction_processor.service;

import org.springframework.stereotype.Component;

import com.cts.transaction_processor.model.Transaction;

@Component
public class TransactionFailureSimulator implements TransientFailureSimulator {

	@Override
	public String failureReason(Transaction transaction, int retryCount) {
		if ("TXN-2005".equals(transaction.getTransactionId()) && retryCount == 0) {
			return "Simulated transient failure for TXN-2005";
		}
		return null;
	}
}
