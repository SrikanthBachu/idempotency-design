package com.cts.transaction_processor.api.dto;

import java.time.Instant;
import java.util.List;

public record ApiErrorDto(
		Instant timestamp,
		int status,
		String error,
		String message,
		String path,
		List<String> details) {

	public ApiErrorDto {
		details = List.copyOf(details);
	}
}
