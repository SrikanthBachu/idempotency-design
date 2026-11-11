package com.cts.transaction_processor.api.dto;

import java.util.List;

import jakarta.validation.constraints.NotEmpty;

public record BatchRequestDto(@NotEmpty(message = "must contain at least one transaction")
		List<TransactionRequestDto> transactions) {
}
