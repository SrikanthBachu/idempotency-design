package com.cts.transaction_processor.api;

import java.math.BigDecimal;
import java.time.Instant;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.cts.transaction_processor.api.dto.AccountBalanceDto;
import com.cts.transaction_processor.api.dto.BatchAcknowledgementDto;
import com.cts.transaction_processor.api.dto.BatchRequestDto;
import com.cts.transaction_processor.api.dto.TransactionStatusDto;
import com.cts.transaction_processor.api.dto.TransactionSummaryDto;
import com.cts.transaction_processor.service.SequenceProcessingResult;

import jakarta.validation.Valid;

@Validated
@RestController
@RequestMapping("/api")
public class TransactionController {

	private final TransactionBatchService batchService;
	private final TransactionApiService transactionApiService;

	public TransactionController(
			TransactionBatchService batchService,
			TransactionApiService transactionApiService) {
		this.batchService = batchService;
		this.transactionApiService = transactionApiService;
	}

	@PostMapping("/transactions/batch")
	public ResponseEntity<BatchAcknowledgementDto> submitBatch(@Valid @RequestBody BatchRequestDto request) {
		String batchId = batchService.submit(request.transactions());
		return ResponseEntity.accepted().body(new BatchAcknowledgementDto(
				batchId,
				"ACCEPTED",
				request.transactions().size(),
				Instant.now(),
				"Batch accepted for background processing"));
	}

	@GetMapping("/transactions/{transactionId}")
	public TransactionStatusDto getTransaction(@PathVariable("transactionId") String transactionId) {
		return transactionApiService.findTransaction(transactionId);
	}

	@GetMapping("/accounts/{accountId}/balance")
	public AccountBalanceDto getBalance(@PathVariable("accountId") String accountId) {
		BigDecimal balance = transactionApiService.findBalance(accountId);
		return new AccountBalanceDto(accountId, balance);
	}

	@GetMapping("/transactions/summary")
	public TransactionSummaryDto getSummary() {
		return transactionApiService.summarize();
	}

	@PostMapping("/transactions/{transactionId}/retry")
	public ResponseEntity<SequenceProcessingResult> retryTransaction(
			@PathVariable("transactionId") String transactionId) {
		return ResponseEntity.status(HttpStatus.OK).body(transactionApiService.retryFailed(transactionId));
	}
}
