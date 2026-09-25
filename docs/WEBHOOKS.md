# Webhook delivery

Implemented endpoints: `POST/GET /api/v1/webhooks`, `DELETE /api/v1/webhooks/{id}` (disable), `GET /api/v1/webhook-deliveries`, `GET /api/v1/webhook-deliveries/{id}/attempts`, `POST /api/v1/webhook-deliveries/{id}/replay`. Dashboard bearer authentication and merchant membership are required; configuration and replay require OWNER/ADMIN. Lists use tenant predicates and keyset pagination. Attempts return up to 100 rows; pass the last attempt number as `before` for older attempts.

Creation accepts `url` and an array of event names in `subscriptions`. It returns the secret once with `Cache-Control: no-store`. Secrets are random 256-bit base64url strings, encrypted using AES-256-GCM and endpoint ID as authenticated associated data. The configured encryption key is external to PostgreSQL. One encryption-key version is currently supported: rotation requires a controlled re-encryption migration, not replacing the key blindly. Disabled endpoints cannot be re-enabled; create a new endpoint/secret instead.

## Local configuration

Run `java scripts/GenerateWebhookKey.java` from the root. The PowerShell backend launcher reads the ignored key file. Other launchers must supply its value as `WEBHOOK_ENCRYPTION_KEY` securely. Configure `WEBHOOK_ALLOWED_ORIGINS` as comma-separated exact origins **including ports**, such as `https://hooks.example.com:443`. No destinations are approved by default. Enable `EVENTS_ENABLED=true` and start the Compose events profile.

For an explicitly approved loopback development receiver only, set `WEBHOOK_ALLOW_LOCAL=true` and approve its exact origin, e.g. `http://127.0.0.1:9099`. Private LAN/container/metadata IPs remain denied. Production must keep local access false and also enforce network egress restrictions. Arbitrary public ports require explicit operator approval through the origin list.

## Guarantees and failure behavior

The Kafka consumer group `ledgerflow-webhooks-v1` commits its processed marker and one job per matching active endpoint atomically. Duplicate events cannot duplicate jobs. Subscriptions are evaluated at consumption time, not event creation time. Endpoint URL and secret are immutable; payload bytes are persisted once and unchanged on retries. Disabling an endpoint suppresses later claims, but cannot cancel a request already in flight.

Workers claim five jobs with 60-second leases, release the database connection, and execute bounded HTTP POSTs. Failed attempts retry at 1, 2, 4, 8, 16, 32 minutes, then hourly, with up to 29 seconds jitter. A cycle permits 32 attempts, covering approximately 26 hours. All non-2xx responses, including redirects, retry; redirects are never followed. Failed jobs remain inspectable indefinitely. Manual replay starts another cycle while retaining event ID, total attempts and immutable completed-attempt history. A crashed attempt may leave a gap in history: attempts count claimed sends, while the history contains acknowledged completion records.

Fenced completion cannot overwrite a newer lease. A crash after the receiver accepts a request can still cause duplicate HTTP delivery. Receivers **must deduplicate event IDs persistently**. Delivery order across events is not guaranteed; use events as facts and retrieve current resource state where needed.

The transport validates every resolved IP immediately before connecting to that exact IP. TLS still verifies the original hostname and sends SNI. Two bounded DNS worker threads and a bounded queue limit stuck resolver work; DNS wait is two seconds. Each connection has a five-second total deadline and three-second read timeout. Response headers are limited to 8 KiB; response bodies are not retained, preventing sensitive response snippets from entering logs/storage. No proxy environment settings or HTTP redirects can redirect this connection. IPv4 private/reserved and non-global/transition IPv6 ranges are rejected conservatively.

## Verify a signature

Read `X-LedgerFlow-Timestamp`, `X-LedgerFlow-Event-Id`, and `X-LedgerFlow-Signature` (`v1=<hex>`). Compute `HMAC-SHA256` with the **literal UTF-8 secret string** over `timestamp + "." + raw request body`. Compare decoded bytes in constant time, reject timestamps outside five minutes, and deduplicate the event ID. Do not parse/re-serialize the body before verification. The timestamp and signature change on every attempt; the persisted JSON body and event ID do not.

Tests exercise a real local HTTP receiver for HMAC, HTTP 500 then success, replay, timeout, blocked destination and tenant isolation, plus Kafka-to-job delivery. Managed TLS/egress infrastructure and encryption-key rotation remain deployment responsibilities.
