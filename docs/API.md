# API boundaries

The routes below are implemented. All routes begin `/api/v1`. Money input uses `amount` in minor units with explicit `currency`; balances/large totals return decimal strings. UUID path IDs do not imply authorization. User selects a merchant with `X-Merchant-Id` and the server checks active membership on every request. API keys have one fixed merchant; an incompatible header is rejected.

Bearer JWT for dashboard requests; merchant keys (`lf_test_<prefix>_<random-secret>`) use a Spring Security authentication converter/provider on the API security chain. Demo prefix intentionally avoids suggesting live payments. HTTPS required outside localhost. API keys do not access dashboard account/member administration.

| Method/path | Purpose | Authorization |
| --- | --- | --- |
| POST /auth/register | Create user, merchant and OWNER membership | Public, IP limited |
| POST /auth/login | Authenticate; return short access token + explicit refresh token | Public; socket-IP limited |
| POST /auth/refresh; POST /auth/logout | Rotate/revoke refresh family | Explicit refreshToken JSON body; no cookies |
| GET /auth/me; GET /merchants | User / available memberships | Dashboard JWT |
| GET/PATCH /merchant | Merchant details | Any member / OWNER or ADMIN |
| GET/POST /merchant/members | List / add existing registered user | Member / OWNER or ADMIN |
| PATCH/DELETE /merchant/members/{userId} | Role/revoke; protect last OWNER | OWNER; ADMIN cannot grant/remove OWNER |
| GET/POST /wallets | List / create customer or settlement wallet | Read / OWNER, ADMIN, DEVELOPER or scoped key |
| GET /wallets/{id}/balance | Projected primary DB balance | Read |
| GET /wallets/{id}/transactions | Cursor history | Read |
| POST /wallets/{id}/funding | Simulate funds; disabled outside demo profile | Write + idempotency |
| GET/POST /transfers; GET /transfers/{id} | List/create/detail | Read/write + idempotency |
| GET/POST /payments; GET /payments/{id} | List/create CREATED/detail | Read/write + idempotency |
| POST /payments/{id}/confirm | Simulated wallet capture | Write + idempotency |
| POST /payments/{id}/cancel | Cancel CREATED only | Write + idempotency |
| GET /refunds; POST /payments/{id}/refunds | List/create compensating refund | Read/write + idempotency |
| GET /refunds/{id} | Refund outcome | Read |
| GET /ledger/accounts; GET /ledger/transactions | Account/journal lists | Read |
| GET /ledger/transactions/{id} | Immutable entries | Read |
| GET/POST /api-keys; DELETE /api-keys/{id} | List metadata, create once-only secret, revoke | OWNER/ADMIN/DEVELOPER dashboard; cannot escalate caller scope |
| GET/POST /webhooks; DELETE /webhooks/{id} | Configure/deactivate endpoint | OWNER/ADMIN dashboard for writes; members may read |
| GET /webhook-deliveries; GET /webhook-deliveries/{id}/attempts | Delivery/attempt history | Read |
| POST /webhook-deliveries/{id}/replay | Requeue terminal delivery for an active endpoint | OWNER/ADMIN, audited; state-guarded |
| GET/POST /reconciliation-runs | Reports/start asynchronous snapshot check | Read / OWNER or ADMIN |
| GET /reconciliation-runs/{id}; GET /reconciliation-runs/{id}/discrepancies | Report and findings with pagination | Read |
| GET /audit-events; GET /operations/outbox; GET /operations/dead-letters; GET /operations/health | Tenant audit/backlog failures | Read; sanitized tenant-only views |
| GET /overview; GET /notifications | Aggregates and activity | Read |

Read means all active merchant roles or read-scoped API keys where a route explicitly permits keys. Mutation means OWNER/ADMIN/DEVELOPER with operation scope, never VIEWER. Reconciliation/DLT administration is not offered to API keys. Platform-wide health/metrics require separate operator/network controls.

Payment request requires exactly one of `customerWalletId` or `customerId`, resolved through the tenant's unique customer mapping. Never accept an ambiguous customer string without lookup. Settlement wallet is configured for the merchant/currency, not supplied as an arbitrary third-party destination. `reference` is searchable but not inherently unique; an order may have multiple attempts. Metadata: max 20 string pairs, key <=64 chars/value <=256, no secrets.

All mutating financial endpoints require Idempotency-Key. Creation returns 201 + Location. Confirm returns 200 with terminal outcome; insufficient funds can be FAILED resource state. Invalid state returns 409; validation 400; unauthenticated 401; unauthorized operation 403; cross-tenant/missing resource 404; throttled 429; dependencies/lock exhaustion 503. Application Problem Details include type, title, status, safe detail, code and correlationId. Standard Spring Security bearer failures use its native response format. Never return SQL or stack traces.

Lists use limit 1..100 (default 25), opaque cursor over immutable `(created_at,id)` and `nextCursor`. Ordering is fixed; no arbitrary SQL sort fields. Payment filters: status and exact reference. Ledger-account/member lists are unpaginated; API-key lists cap at 100 and webhook-attempt lists cap at 100 with an attempt cursor. All repository reads carry merchant_id from the security context, never trusted body input. Request IDs are server-generated UUIDs, echoed as X-Request-Id.

Implemented webhook API details and signature verification: [WEBHOOKS.md](WEBHOOKS.md).

## Developer tools

Set API_DOCS_ENABLED=true locally and open http://localhost:8080/swagger-ui/index.html or /v3/api-docs directly on the backend. Generated routes/DTOs are verified by integration test; some replayed financial JSON responses have generic schemas, so this document and the executable journey remain the semantic reference. Swagger is disabled by default.

Run node scripts/seed-demo.mjs against the demo Compose app for an executable API journey including merchant registration, memberships, wallets, funding, API-key payment capture, partial/full refunds and reconciliation. It writes generated login credentials and one-time secrets only to an ignored .local-secrets/demo-<uuid>.json file. Each run creates a new tenant; it does not overwrite prior demo data. Optional DEMO_WEBHOOK_URL must already be operator-allowlisted. Financial request keys are retained for investigation after ambiguous failures. See API-EXAMPLES.md for curl examples.
