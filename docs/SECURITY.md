# Security

LedgerFlow simulates money and assumes hostile API clients and webhook destinations. It does not implement identity proofing, fraud detection, custody, compliance certification or real payment authorization.

## Identity and tenant boundaries

Spring Security validates RS256 JWT signatures, issuer, audience and expiry. Access tokens last ten minutes. RSA signing keys are loaded from external files and must be at least 3072 bits; the current implementation uses one configured key pair and kid, without overlapping-key rotation or a JWKS endpoint. Every dashboard application operation verifies the active session family/user and current merchant membership in PostgreSQL. UUIDs are identifiers, not capabilities. API keys fix the merchant identity; incompatible merchant headers are rejected. Queries include tenant predicates, and composite foreign keys prevent cross-tenant financial references.

Passwords use Argon2id: 19,456 KiB memory, two iterations, parallelism one, 16-byte salt and 32-byte output. Login does a dummy hash check for missing users and upgrades hashes on success when needed. Refresh secrets have 256 random bits, are SHA-256 hashed in PostgreSQL, expire in seven days, and rotate once per use. Family-root locking makes concurrent reuse revocation atomic. Tokens are returned in no-store JSON responses. The dashboard keeps them in memory and requires login after reload. Refresh/logout accept explicit JSON tokens; no credential cookies or server sessions are used, so CSRF is disabled. A future cookie-based flow must add origin/CSRF controls first.

OWNER and ADMIN manage merchant settings; DEVELOPER can create financial commands and API keys; VIEWER reads permitted resources. Last-owner removal is rejected under a merchant lock. API keys cannot access dashboard management routes. Tests cover tenant isolation, read-only scopes, revoked keys, viewer writes, owner preservation and session reuse. PostgreSQL row-level security is not implemented; isolation uses explicit services/queries and constraints.

API keys use a public lookup prefix plus a 256-bit secret. Only SHA-256 hashes are stored, compared in constant time; full secrets appear once at issuance. Created/last-used/revoked timestamps and audit entries persist. The demo prefix lf_test deliberately makes no real-money claim. API-key hashing differs from password hashing because these secrets have high entropy.

## Webhooks

HMAC-SHA256 signs timestamp + '.' + exact stored JSON. Timestamp/signature change per attempt; event ID stays stable. Receivers should check a five-minute timestamp window, compare in constant time, and persist event-ID deduplication. Webhook secrets are recoverable for signing, so they use AES-256-GCM with endpoint-associated data and random nonces. Encryption key rotation is not implemented; loss of the external key prevents signing existing endpoints.

Destinations require an exact operator-approved origin, HTTPS by default, and no credentials/fragments. All resolved addresses are validated against private/reserved ranges and the transport connects to a vetted address while retaining certificate hostname verification/SNI. Redirects are refused, DNS/connect/read budgets and response-header limits are bounded, and response bodies are discarded. Explicit local development allowlists can enable HTTP/loopback; production Compose/Helm do not enable that switch. Network egress policy remains an operator responsibility. See WEBHOOKS.md for transport limits.

## Request and deployment controls

Redis limits socket IPs before authentication and verified principals afterward. Redis failure returns retryable 503 for all API requests before business processing; it never becomes a financial store. See REDIS.md for exact TTLs, keys, caps and the shared-proxy limitation. Account-specific lockout, CAPTCHA, MFA, email verification, reset/recovery and distributed DDoS defense are not implemented.

Parameterized SQL and validated DTOs prevent raw request interpolation into SQL. React renders text without unsafe HTML. Nginx sets CSP, frame denial and content-type/referrer headers and caps request bodies at 128 KiB. It allows inline styles for the chart library, not inline scripts. Cross-origin browser access is not enabled; the packaged frontend uses a same-origin proxy. Keep direct API ingress private or enforce equivalent body limits at the edge. TLS termination, trusted proxy policy, managed Kafka/Redis credentials and network policies must be configured for the deployment.

Application errors avoid SQL and stack traces. Spring Security's standard bearer errors are not all normalized into the application Problem Details envelope. Logs use ECS JSON and request IDs; no application request/credential/body logging is enabled. The Kafka producer listener emits sanitized failures. Enabling library DEBUG logs can expose records and must be reviewed. Metrics have separate operator credentials; blank credentials deny access. Swagger/OpenAPI are opt-in and disabled by default.

Runtime PostgreSQL credentials cannot mutate ledger history/projections directly or alter schema. The separate migration job owns DDL privileges. Containers run non-root with read-only root filesystems, dropped capabilities and no Kubernetes service-account token. Local secrets are ignored, generators never overwrite keys, and release secrets must come from an external store. Financial/audit immutability does not constrain a privileged database administrator; use restricted admin access, backups and external archives for that threat.

## Review and reporting

Java/npm dependencies are locked; the Gradle distribution has a checksum. CI compiles with warnings as errors, checks module boundaries and runs integration/browser tests. Dependency advisories still require review; the repository does not claim a complete automated backend vulnerability scan, penetration test or certification. Reproduce suspected vulnerabilities with simulated data and report privately to the repository maintainer without posting live keys or personal data. Patch releases require tests and deployment-specific backup/restore and key-custody checks.

## OIDC integration path (not implemented)

A future identity-provider integration should use authorization code with PKCE, state/nonce validation and pinned issuer/JWKS/audience. Map the immutable issuer+subject pair to a local user through an explicit federated-identity table; do not silently link accounts solely by email. After verified login, create the local session family and issue the existing short access token, preserving current PostgreSQL session revocation and merchant authorization checks. Directly accepting an arbitrary provider token would bypass the current sid/session contract and requires a separate reviewed design. Keycloak is not a local runtime dependency.
