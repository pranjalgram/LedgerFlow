# Concurrency and transaction boundaries

## Strategy

PostgreSQL READ COMMITTED plus pessimistic row locking serializes contenders for the same ledger accounts. Balance checks occur after acquiring locks. Acquire all participating account rows in canonical UUID order. Do not lock source then destination in user-supplied order: opposing transfers would deadlock. JDBC `SELECT ... ORDER BY id FOR UPDATE` in the posting function makes ordering explicit; JPA can lock the payment aggregate using PESSIMISTIC_WRITE.

Global order: idempotency claim -> payment row if applicable -> account rows sorted by ID -> inserts (business, journal, entries, audit, outbox) -> stored response. Refunds always acquire payment before accounts. Ledger code never calls back into payment. Funding, transfers, and payment capture use the same posting function. Role/membership authorization happens before entering the command and is rechecked where concurrent revocation matters.

Use bounded lock/statement/transaction timeouts (initial targets: 3s / 10s / 15s, tune after tests), a bounded connection pool, and return a retriable Problem Details error on lock timeout. Never retry only the second half of a transaction. Deadlocks/serialization failures may retry the entire command with the same idempotency key, at most twice with jitter, outside the transactional proxy. Insufficient funds is a business outcome, not a retry candidate.

| Operation | One atomic transaction |
| --- | --- |
| Register | User + merchant + OWNER membership + audit |
| Create wallet | Wallet + liability account + audit |
| Fund | Idempotency + control/wallet locks + funding record + posting/projection + audit + outbox + response |
| Transfer | Idempotency + account locks + transfer + posting/projection + audit + outbox + response |
| Create payment | Idempotency + CREATED payment + transition + audit + outbox + response |
| Confirm payment | Idempotency + payment lock + account locks + state transitions + posting or failure outcome + audit + outbox + response |
| Refund | Idempotency + payment lock + refundable validation + account locks + refund + posting/projection + refunded total/state + audit + outbox + response |
| Consume event | ProcessedEvent claim + new deliveries/notifications + commit, then offset acknowledgement |
| Webhook attempt | Claim transaction; HTTP outside transaction; fenced result transaction |

Use application-service proxy boundaries or TransactionTemplate; do not call a transactional method on `this`. Financial methods cannot run without an active transaction. No broker/HTTP request takes place while financial locks are held. Cache reads never decide available balance.

## Proof obligations

Real PostgreSQL Testcontainers tests must run concurrent commands on independent connections. Seed 1,000,000 minor units; release 100 spending commands of 100,000 using a barrier and enough worker threads without blocking on a barrier larger than the executor. Exactly ten succeed, ninety have insufficient funds, balance is zero, all journals balance, and destination sum equals initial funds. Use a sufficient test pool/timeout to distinguish rejection from pool starvation. Also test opposite-direction transfers, concurrent duplicate requests, concurrent partial refunds, rollback after posting, and projection consistency.

READ COMMITTED is sufficient because all balance writers lock the same authoritative rows. It does not protect arbitrary unlocked reads. Reconciliation/reporting uses a separate consistent snapshot. A hot account serializes by design and is a real throughput limit; Redis locks or more API replicas do not remove it.
