package com.cts.transaction_processor.api;

import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.isEmptyOrNullString;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;

import com.cts.transaction_processor.model.Account;
import com.cts.transaction_processor.model.Transaction;
import com.cts.transaction_processor.model.TransactionStatus;
import com.cts.transaction_processor.model.TransactionType;
import com.cts.transaction_processor.repository.AccountRepository;
import com.cts.transaction_processor.repository.TransactionRepository;

@SpringBootTest
@AutoConfigureMockMvc
class TransactionControllerTests {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private AccountRepository accountRepository;

	@Autowired
	private TransactionApiService transactionApiService;

	@Autowired
	private TransactionRepository transactionRepository;

	@BeforeEach
	void initializeAccount() {
		accountRepository.save(new Account("api-account", "USD", new BigDecimal("25.00")));
	}

	@Test
	void acceptsBatchAndReturnsBackgroundAcknowledgement() throws Exception {
		mockMvc.perform(post("/api/transactions/batch")
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"transactions":[
								  {"transactionId":"api-txn-1","accountId":"api-account","requestId":"api-req-1",
								   "transactionType":"CREDIT","amount":5.00,"currency":"USD","sequenceNumber":1}
								]}
								"""))
				.andExpect(status().isAccepted())
				.andExpect(jsonPath("$.batchId", not(isEmptyOrNullString())))
				.andExpect(jsonPath("$.status").value("ACCEPTED"))
				.andExpect(jsonPath("$.acceptedCount").value(1));
		org.junit.jupiter.api.Assertions.assertTrue(transactionRepository.findById("api-txn-1").isPresent());
	}

	@Test
	void returnsMeaningfulValidationErrorForEmptyBatch() throws Exception {
		mockMvc.perform(post("/api/transactions/batch")
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"transactions\":[]}"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.message").value("Request validation failed"))
				.andExpect(jsonPath("$.details[0]").exists());
	}

	@Test
	void returnsAccountBalanceAndSummary() throws Exception {
		mockMvc.perform(get("/api/accounts/api-account/balance"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.accountId").value("api-account"))
				.andExpect(jsonPath("$.balance").value(25.00));

		mockMvc.perform(get("/api/transactions/summary"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.total").isNumber())
				.andExpect(jsonPath("$.countsByStatus").exists());
	}

	@Test
	void summaryCountsPersistedTransactionStatuses() throws Exception {
		Instant now = Instant.now();
		Transaction received = new Transaction(
				"api-summary-" + UUID.randomUUID(), "api-summary-request-" + UUID.randomUUID(),
				"api-account", TransactionType.CREDIT, new BigDecimal("1.00"), "USD", 99,
				TransactionStatus.RECEIVED, 0, now, now);
		transactionRepository.saveAndFlush(received);
		long persistedReceived = transactionRepository.findByStatus(TransactionStatus.RECEIVED).size();

		mockMvc.perform(get("/api/transactions/summary"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.countsByStatus.RECEIVED").value(persistedReceived));
	}

	@Test
	void returnsNotFoundErrorForUnknownTransaction() throws Exception {
		mockMvc.perform(get("/api/transactions/missing-transaction"))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.message").value("Transaction not found: missing-transaction"));
	}

	@Test
	void readsPersistedTransactionStatusFromH2() throws Exception {
		String suffix = UUID.randomUUID().toString();
		String accountId = "api-read-account-" + suffix;
		String transactionId = "api-read-transaction-" + suffix;
		accountRepository.save(new Account(accountId, "USD", new BigDecimal("25.00")));
		Instant now = Instant.now();
		transactionApiService.process(new Transaction(
				transactionId, "api-read-request-" + suffix, accountId,
				TransactionType.CREDIT, new BigDecimal("5.00"), "USD", 1,
				TransactionStatus.PENDING, 0, now, now));

		mockMvc.perform(get("/api/transactions/{transactionId}", transactionId))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.transactionId").value(transactionId))
				.andExpect(jsonPath("$.accountId").value(accountId))
				.andExpect(jsonPath("$.status").value("PROCESSED"))
				.andExpect(jsonPath("$.retryCount").value(0))
				.andExpect(jsonPath("$.failureReason").value(nullValue()));
	}

	@Test
	void rejectsRetryForUnknownTransaction() throws Exception {
		mockMvc.perform(post("/api/transactions/missing-transaction/retry"))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.message").value("Transaction not found: missing-transaction"));
	}

	@Test
	void returnsConflictForRetryOfIneligiblePersistedTransaction() throws Exception {
		String suffix = UUID.randomUUID().toString();
		Instant now = Instant.now();
		Transaction transaction = new Transaction(
				"api-ineligible-" + suffix, "api-ineligible-request-" + suffix, "api-account",
				TransactionType.CREDIT, new BigDecimal("5.00"), "USD", 1,
				TransactionStatus.RECEIVED, 0, now, now);
		transactionApiService.acceptBatch(List.of(transaction));

		mockMvc.perform(post("/api/transactions/{transactionId}/retry", transaction.getTransactionId()))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.status").value(409))
				.andExpect(jsonPath("$.message")
						.value("Transaction is not eligible for retry"));
	}

	@Test
	void retryEndpointPersistsSuccessfulRetryStatusAndCount() throws Exception {
		String suffix = UUID.randomUUID().toString();
		String transactionId = "api-retry-" + suffix;
		Instant now = Instant.now();
		Transaction failed = new Transaction(
				transactionId, "api-retry-request-" + suffix, "api-account",
				TransactionType.CREDIT, new BigDecimal("5.00"), "USD", 1,
				TransactionStatus.FAILED, 3, now, now);
		failed.setFailureReason("Transient downstream failure");
		transactionRepository.saveAndFlush(failed);

		mockMvc.perform(post("/api/transactions/{transactionId}/retry", transactionId))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("PROCESSED"))
				.andExpect(jsonPath("$.executions[0].executionResult.status").value("PROCESSED"))
				.andExpect(jsonPath("$.executions[0].executionResult.retryCount").value(0));

		Transaction persisted = transactionRepository.findById(transactionId).orElseThrow();
		org.junit.jupiter.api.Assertions.assertEquals(TransactionStatus.PROCESSED, persisted.getStatus());
		org.junit.jupiter.api.Assertions.assertEquals(0, persisted.getRetryCount());
		org.junit.jupiter.api.Assertions.assertNull(persisted.getFailureReason());
	}

	@Test
	void returnsConflictForReusedTransactionIdWithDifferentPayload() throws Exception {
		String suffix = UUID.randomUUID().toString();
		Instant now = Instant.now();
		Transaction original = new Transaction(
				"api-conflict-" + suffix, "api-conflict-request-" + suffix, "api-account",
				TransactionType.CREDIT, new BigDecimal("5.00"), "USD", 1,
				TransactionStatus.RECEIVED, 0, now, now);
		transactionApiService.acceptBatch(List.of(original));

		mockMvc.perform(post("/api/transactions/batch")
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"transactions":[
								  {"transactionId":"%s","accountId":"api-account","requestId":"changed-%s",
								   "transactionType":"CREDIT","amount":6.00,"currency":"USD","sequenceNumber":1}
								]}
								""".formatted(original.getTransactionId(), suffix)))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.status").value(409))
				.andExpect(jsonPath("$.message")
						.value("transactionId is already associated with a different payload"));
	}
}
