package com.cts.transaction_processor.api.dto;

import java.math.BigDecimal;

public record AccountBalanceDto(String accountId, BigDecimal balance) {
}
