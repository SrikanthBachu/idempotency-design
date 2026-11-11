package com.cts.transaction_processor.startup;

import java.math.BigDecimal;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.cts.transaction_processor.model.Account;
import com.cts.transaction_processor.repository.AccountRepository;

@Component
@ConditionalOnProperty(name = "app.data.initialize", havingValue = "true")
public class SampleDataInitializer implements ApplicationRunner {

	private static final Logger logger = LoggerFactory.getLogger(SampleDataInitializer.class);

	private final AccountRepository accountRepository;

	public SampleDataInitializer(AccountRepository accountRepository) {
		this.accountRepository = accountRepository;
	}

	@Override
	@Transactional
	public void run(ApplicationArguments args) {
		logger.info("Database initialization started");

		List<Account> sampleAccounts = List.of(
				new Account("ACC-201", "INR", new BigDecimal("1000.00")),
				new Account("ACC-202", "INR", new BigDecimal("1000.00")),
				new Account("ACC-203", "INR", new BigDecimal("1000.00")));

		int initialized = 0;
		int skipped = 0;
		for (Account account : sampleAccounts) {
			if (accountRepository.existsById(account.getAccountId())) {
				skipped++;
			} else {
				accountRepository.save(account);
				initialized++;
			}
		}

		logger.info("Database initialization accounts: {} initialized, {} existing records skipped",
				initialized, skipped);
		logger.info("Database initialization completed");
	}
}
