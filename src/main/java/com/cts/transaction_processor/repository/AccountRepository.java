package com.cts.transaction_processor.repository;

import java.util.Optional;

import jakarta.persistence.LockModeType;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.cts.transaction_processor.model.Account;

public interface AccountRepository extends JpaRepository<Account, String> {

	Optional<Account> findByCurrency(String currency);

	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select account from Account account where account.accountId = :accountId")
	Optional<Account> findByIdForUpdate(@Param("accountId") String accountId);
}
