package com.cts.transaction_processor.service;

public record SequenceGap(long firstMissingSequence, long lastMissingSequence) {
}
