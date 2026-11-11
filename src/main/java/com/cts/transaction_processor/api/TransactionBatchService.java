package com.cts.transaction_processor.api;

import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.Executor;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import com.cts.transaction_processor.api.dto.TransactionRequestDto;
import com.cts.transaction_processor.model.Transaction;

@Service
public class TransactionBatchService {

	private static final Logger LOGGER = LoggerFactory.getLogger(TransactionBatchService.class);

	private final TransactionApiService transactionApiService;
	private final Executor batchExecutor;

	public TransactionBatchService(
			TransactionApiService transactionApiService,
			@Qualifier("transactionBatchExecutor") Executor batchExecutor) {
		this.transactionApiService = Objects.requireNonNull(transactionApiService, "transactionApiService must not be null");
		this.batchExecutor = Objects.requireNonNull(batchExecutor, "batchExecutor must not be null");
	}

	public String submit(List<TransactionRequestDto> requests) {
		List<Transaction> transactions = requests.stream()
				.map(request -> request == null ? null : request.toTransaction())
				.toList();
		String batchId = UUID.randomUUID().toString();
		batchExecutor.execute(() -> processBatch(batchId, transactions));
		return batchId;
	}

	private void processBatch(String batchId, List<Transaction> transactions) {
		LOGGER.info("Started transaction batch: batchId={}, count={}", batchId, transactions.size());
		for (Transaction transaction : transactions) {
			try {
				transactionApiService.process(transaction);
			} catch (RuntimeException exception) {
				LOGGER.error("Unexpected failure processing batch record: batchId={}, transactionId={}, reason={}",
						batchId,
						transaction == null ? null : transaction.getTransactionId(),
						exception.getMessage(),
						exception);
			}
		}
		LOGGER.info("Completed transaction batch: batchId={}, count={}", batchId, transactions.size());
	}
}
