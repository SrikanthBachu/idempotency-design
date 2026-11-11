# Read Me First
The following was discovered as part of building this project:

* The original package name 'com.cts.transaction-processor' is invalid and this project uses 'com.cts.transaction_processor' instead.

# Getting Started

### Reference Documentation
For further reference, please consider the following sections:

* [Official Apache Maven documentation](https://maven.apache.org/guides/index.html)
* [Spring Boot Maven Plugin Reference Guide](https://docs.spring.io/spring-boot/4.2.0-SNAPSHOT/maven-plugin)
* [Create an OCI image](https://docs.spring.io/spring-boot/4.2.0-SNAPSHOT/maven-plugin/build-image.html)

### Maven Parent overrides

Due to Maven's design, elements are inherited from the parent POM to the project POM.
While most of the inheritance is fine, it also inherits unwanted elements like `<license>` and `<developers>` from the parent.
To prevent this, the project POM contains empty overrides for these elements.
If you manually switch to a different parent and actually want the inheritance, you need to remove those overrides.

### Request deduplication and business idempotency

`requestId` identifies an individual API/request attempt. Repeating the same request ID and payload is request-level
deduplication and returns the prior outcome. `transactionId` identifies the business transaction itself. A retry or
resubmission with a new request ID but the same transaction ID and payload is a business-level duplicate and must not
apply the balance change again; reusing that transaction ID with a different business payload is a conflict.

### Transient transaction retries

`TransactionRetryService` retries transient execution failures up to three times (in addition to the initial attempt).
Each failed attempt sets the transaction to `RETRY_PENDING` and logs its retry count and failure reason. A successful
attempt ends in `PROCESSED`; exhausting retries ends in `FAILED`. The built-in failure simulator fails `TXN-2005` on
its initial attempt, then allows the retry to succeed. Transient attempts happen before applying a balance change, and
the idempotency check prevents later duplicate requests from applying that change again.

### Transaction processor architecture

`TransactionProcessor` is the entry point for single requests, batches, and pending-sequence recovery. It delegates
ordering and validation to `SequenceProcessingService`, which calls `TransactionRetryService` only when a record is at
the next sequence. The retry service marks attempts and invokes `IdempotencyService`; that service calculates
`BigDecimal` balance deltas for CREDIT, DEBIT, and REVERSAL, checks business/request idempotency, and coordinates the
account and transaction repositories. `TransactionResultRepository` stores each submission outcome and each sequence
execution result. Batch submissions are handled individually so an exception for one record is logged and persisted as
a failure result without aborting later records.

The in-memory processing boundary is the synchronized idempotency operation: duplicate checks, balance adjustment,
and idempotency recording run serially, and the balance is compensated if recording fails. Sequence state is guarded
by a per-account monitor; different accounts can be sequenced independently. Retry execution is synchronized across
the service, favoring correctness over throughput. These maps do not provide a durable ACID transaction or cross-node
locking. For production persistence, store account balance, idempotency keys, transaction status, and result records in
one database transaction, enforce unique constraints on `transactionId` and `requestId`, and use optimistic/pessimistic
locking or a per-account distributed lock for sequence advancement.

The REST API accepts `POST /api/transactions/batch` with a non-empty `transactions` array and immediately returns
`202 Accepted` with a batch ID while a bounded worker pool processes records. Record validation happens per item, so
one invalid transaction does not reject or halt the rest of the accepted batch. Transaction status, account balance,
status summary, and retry operations are available through the corresponding `/api/transactions/{transactionId}`,
`/api/accounts/{accountId}/balance`, `/api/transactions/summary`, and
`/api/transactions/{transactionId}/retry` endpoints. Retry is limited to transactions that exhausted transient retries.
The batch executor and all API repositories are in-memory; batch acknowledgements and result/status data are not durable
across process restarts.

### Transaction sequencing and pending recovery

`SequenceProcessingService` orders transactions per account, starting at sequence `1`. It buffers future sequence
numbers, reports missing sequence ranges, and calls `IdempotencyService` only when a record reaches the next expected
sequence. Validation failures with a usable account ID and positive sequence number are terminal records: their
validation errors are returned, but they still advance the sequence so one invalid record cannot block later records.
Records without a usable account ID or sequence number cannot be placed in the sequence and are returned as
`UNSEQUENCABLE`.

For example, with an account balance of `100.00`, submit:

| Arrival | Transaction | Sequence | Type | Amount | Outcome |
|---|---|---:|---|---:|---|
| 1 | `txn-3` | 3 | CREDIT | 30.00 | Buffered; sequences 1-2 are missing |
| 2 | `txn-1` | 1 | CREDIT | 10.00 | Processed; sequence 2 is missing |
| 3 | `txn-2` | 2 | CREDIT | 0.00 | Terminal validation error; then buffered sequence 3 is processed |

The final balance is `140.00`; execution order is sequence 1, terminal-invalid sequence 2, then sequence 3.
The repository does not include a separate assessment fixture, so this sample is also exercised by
`SequenceProcessingServiceTests`.

Buffered work is exposed by `getPendingTransactions(accountId)` and gaps by the `missingSequences` result field.
`recoverPending(accountId)` retries only records whose predecessors are already available; it never skips a gap.
The current repositories and buffer are in-memory. To survive a process restart, persist the next expected sequence
and pending transaction payloads durably, then reload/re-submit those records during startup before accepting new work.
