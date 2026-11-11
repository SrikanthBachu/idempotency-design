package com.cts.transaction_processor.api.dto;

import java.util.Map;

import com.cts.transaction_processor.model.TransactionStatus;

public record TransactionSummaryDto(long total, Map<TransactionStatus, Long> countsByStatus) {

	public TransactionSummaryDto {
		countsByStatus = Map.copyOf(countsByStatus);
	}
}
