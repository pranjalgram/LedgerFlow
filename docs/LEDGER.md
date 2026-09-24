# Ledger and money

Status: design; these guarantees require migration and concurrency tests before implementation acceptance.

## Units

Use positive signed 64-bit integer minor units in Java `long` and PostgreSQL `bigint`. `Money(amountMinor, CurrencyCode)` performs checked arithmetic (`Math.addExact`/`subtractExact`). INR is the only initial supported currency, exponent 2. A currency reference table stores exponent and enabled status; enabling another currency requires tests, accounts, and UI formatting support. No implied universal two-decimal rule or exchange conversion.

Bound public individual amounts to 1..9,000,000,000,000 minor units; enforce bound in validation and SQL. Aggregate sums use PostgreSQL numeric/Java BigInteger before checked conversion. JSON accepts safe integer amounts only; the chosen cap is below JavaScript MAX_SAFE_INTEGER. Render very large aggregate balances from decimal strings to avoid frontend precision loss.

## Accounting perspective

The books belong to the simulated platform. A wallet is a liability: the platform owes its holder the wallet balance. Credits increase liabilities; debits reduce them. The simulated cash control account is an asset with debit normal balance. Every tenant has one cash control account per currency. It represents simulated backing, never actual bank cash.

| Operation | Debit | Credit |
| --- | --- | --- |
| Fund customer 100,000 minor units | Tenant simulated cash asset 100,000 | Customer liability 100,000 |
| Transfer A to B 20,000 | Wallet A liability 20,000 | Wallet B liability 20,000 |
| Capture payment 49,900 | Customer liability 49,900 | Settlement liability 49,900 |
| Refund 10,000 | Settlement liability 10,000 | Original customer liability 10,000 |

Liability balance = credits - debits; asset balance = debits - credits. Negative wallet balances are prohibited. Reject same-account transfers, currency mismatch, inactive accounts, cross-tenant references, and arithmetic overflow. An unavailable settlement balance prevents refund; do not silently grant credit.

## Posting invariants

- A posted journal has at least two entries, positive amounts, one tenant, one currency, and exactly equal total debits and credits.
- Each journal has a unique business reference `(merchant_id, business_type, business_id)`. Retrying under a different HTTP key still cannot post the same payment/refund twice.
- Composite foreign keys enforce account/journal tenant and currency agreement.
- Transactions and entries are append-only after commit. UPDATE/DELETE/TRUNCATE privileges are absent on ledger/audit history; mutation triggers are defense in depth.
- Posting creates the journal and all entries atomically. A deferred constraint trigger checks totals and count at commit, including a trigger on journal creation so zero-entry journals cannot commit.
- An insert guard permits entries only for a journal created in the current database transaction, using a server-set `creation_xid xid8 = pg_current_xact_id()` inaccessible to ordinary callers. Journals cannot be reopened after commit. No mutable DRAFT financial journal remains.
- The database posting function locks all affected accounts in sorted UUID order, validates available funds, inserts entries, and updates the derived balance. Runtime role receives EXECUTE on this function, no direct ledger history/balance writes. Function owner is a non-login role, fixed search_path, schema-qualified objects; migration owner is separate.
- Java validates a posting before calling SQL. Database constraints/functions are the final correctness boundary, including for scripts using the runtime role.

## Reversals

A reversal creates a new journal with inverted sides and `reverses_transaction_id`, preserving the original journal. Only a full reversal uses that link, unique per original journal. Partial refunds instead reference the payment and create their own compensating journal. Never mark a historical entry deleted or overwrite it. Reversal can fail if it would overdraw a liability; administrative reconciliation does not bypass this constraint.

## Balances and history

Keep the projection on `ledger_account.balance_minor`; wallet refers to its account and has no competing balance. GET balance reads the projection from the primary database and returns currency, decimal-string balance, account version, and as-of time. History joins immutable entries/journals with keyset pagination ordered by `(posted_at, id)`. This is display order; backdated postings are not supported. Ledger aggregation is authoritative and reconciliation compares it to the projection under one REPEATABLE READ snapshot.

Posting and projection updates share the command transaction with business state, idempotency, audit, and outbox. Ledger immutability protects against ordinary runtime access, not a database superuser; privileged maintenance requires recorded procedures and independent backups.
