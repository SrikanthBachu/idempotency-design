package com.cts.transaction_processor.api;

import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.isEmptyOrNullString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;

import com.cts.transaction_processor.repository.AccountRepository;

@SpringBootTest
@AutoConfigureMockMvc
class TransactionControllerTests {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private AccountRepository accountRepository;

	@BeforeEach
	void initializeAccount() {
		accountRepository.saveBalance("api-account", new BigDecimal("25.00"));
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
	void returnsNotFoundErrorForUnknownTransaction() throws Exception {
		mockMvc.perform(get("/api/transactions/missing-transaction"))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.message").value("Transaction not found: missing-transaction"));
	}

	@Test
	void rejectsRetryForUnknownTransaction() throws Exception {
		mockMvc.perform(post("/api/transactions/missing-transaction/retry"))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.message").value("Transaction not found: missing-transaction"));
	}
}
