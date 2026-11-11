package com.cts.transaction_processor.api.dto;

import java.time.Instant;

public record BatchAcknowledgementDto(
		String batchId,
		String status,
		int acceptedCount,
		Instant acceptedAt,
		String message) {
}
