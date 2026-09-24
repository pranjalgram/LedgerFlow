# Persistent idempotency

Scope keys by `(merchant_id, operation, key)` where operation includes API version, method, route, and target resource. Keys are case-sensitive printable ASCII, 8..128 bytes. Authentication/authorization runs before replay; revoked keys and removed members cannot retrieve a previously stored response. Merchant integrations and authorized dashboard users share merchant operation scope intentionally.

Parse and validate DTOs, normalize amounts/currency/UUIDs, sort metadata keys, and hash canonical serialized content with SHA-256. Include all behavior-changing fields and route parameters. Reject unknown fields to prevent silently ignored behavior. Store a fingerprint version. Different JSON whitespace/key order must not change the fingerprint; absent/null handling is defined per DTO. Never fingerprint authentication headers or retain API secrets.

Within the same financial transaction, `INSERT ... ON CONFLICT DO NOTHING` claims the unique key. PostgreSQL waits on an uncommitted conflicting insert. A winner executes once and stores HTTP status, bounded response JSON and Location with the resource ID. A loser then reads the committed row: matching fingerprint returns the original status/body/Location with `Idempotency-Replayed: true`; different fingerprint returns 409. A timeout returns retriable 409/503 with Retry-After. No independently committed IN_PROGRESS record is required, so process death cannot strand a claim.

Do not catch unique-constraint exceptions inside a now-aborted PostgreSQL transaction. Use ON CONFLICT and inspect affected rows. If the winner rolls back, the waiting insert can claim and execute. Business denials that are normal outcomes can persist a response without posting; validation/auth failures occur before claiming; unexpected failures roll back and are safe to retry.

Retain full response for 30 days; retain compact `(scope,key,fingerprint,resource,status)` tombstone for the lifetime of the financial record. After response expiry return 409 `idempotency-response-expired` with authorized resource link, never execute the same key again. Nonfinancial keys may expire independently with documented policy. This costs storage but avoids a late retry silently duplicating money movement. Request keys never live solely in Redis.

The journal's unique business reference independently prevents duplicate posting. Idempotency-key reuse and business-resource confirmation are separate safeguards. Do not attempt to reuse a key to change an insufficient-funds outcome after funding; issue a new command/key as documented.
