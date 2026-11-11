package com.cts.transaction_processor.startup;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;

import com.cts.transaction_processor.model.Account;
import com.cts.transaction_processor.repository.AccountRepository;

@SpringBootTest(properties = {
		"spring.datasource.url=jdbc:h2:mem:sample-data-initializer-tests;DB_CLOSE_DELAY=-1",
		"app.data.initialize=true"
})
@AutoConfigureMockMvc
class SampleDataInitializerTests {

	@Autowired
	private AccountRepository accountRepository;

	@Autowired
	private SampleDataInitializer initializer;

	@Autowired
	private MockMvc mockMvc;

	@Test
	void initializesSampleAccountsIdempotentlyWithoutOverwritingBalances() throws Exception {
		assertAccount("ACC-201", "INR", "1000.0000");
		assertAccount("ACC-202", "INR", "1000.0000");
		assertAccount("ACC-203", "INR", "1000.0000");

		mockMvc.perform(get("/api/accounts/ACC-201/balance"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.accountId").value("ACC-201"))
				.andExpect(jsonPath("$.balance").value(1000.0));

		long accountCount = accountRepository.count();
		runInitializer();
		assertEquals(accountCount, accountRepository.count());

		Account existing = accountRepository.findById("ACC-201").orElseThrow();
		existing.setBalance(new BigDecimal("1234.56"));
		accountRepository.saveAndFlush(existing);

		runInitializer();
		assertEquals(accountCount, accountRepository.count());
		assertEquals(0, new BigDecimal("1234.5600")
				.compareTo(accountRepository.findById("ACC-201").orElseThrow().getBalance()));
	}

	private void assertAccount(String accountId, String currency, String balance) {
		Account account = accountRepository.findById(accountId).orElseThrow();
		assertEquals(currency, account.getCurrency());
		assertEquals(0, new BigDecimal(balance).compareTo(account.getBalance()));
	}

	private void runInitializer() throws Exception {
		ApplicationArguments arguments = new DefaultApplicationArguments(new String[0]);
		initializer.run(arguments);
	}
}

@SpringBootTest(properties = {
		"spring.datasource.url=jdbc:h2:mem:sample-data-initializer-disabled-tests;DB_CLOSE_DELAY=-1",
		"app.data.initialize=false"
})
class SampleDataInitializerDisabledTests {

	@Autowired
	private AccountRepository accountRepository;

	@Autowired(required = false)
	private SampleDataInitializer initializer;

	@Test
	void doesNotCreateSampleAccountsWhenInitializationIsDisabled() {
		assertFalse(accountRepository.existsById("ACC-201"));
		assertFalse(accountRepository.existsById("ACC-202"));
		assertFalse(accountRepository.existsById("ACC-203"));
		assertTrue(initializer == null);
	}
}
