# API examples

The executable collection is `node scripts/seed-demo.mjs` (Node 24). It creates a new Acme merchant with owner/developer/viewer access, funds a customer wallet, transfers funds, creates an API key, captures successful/failed payments, issues partial/full refunds, checks final states and waits for reconciliation to pass. It uses actual HTTP requests, no direct SQL or fake responses. Run against the Compose demo profile; credentials and financial retry keys stay in an ignored owner-readable local file. Repeated runs create independent tenants.

For manual requests, set shell variables privately from responses or the seed file. Do not paste real secrets into scripts or commit an exported collection containing them. Examples use curl and jq in a POSIX shell:

```sh
BASE=http://localhost:8088/api/v1
# EMAIL and PASSWORD are local values you choose; this registration creates the merchant.
jq -n --arg email "$EMAIL" --arg password "$PASSWORD" \
  '{email:$email,password:$password,merchantName:"Acme Commerce"}' |
  curl --fail-with-body -sS "$BASE/auth/register" -H 'Content-Type: application/json' --data-binary @-

# Extract accessToken privately as TOKEN; use registration's merchantId as MERCHANT.
jq -n --arg email "$EMAIL" --arg password "$PASSWORD" '{email:$email,password:$password}' |
  curl --fail-with-body -sS "$BASE/auth/login" -H 'Content-Type: application/json' --data-binary @-

curl --fail-with-body -sS "$BASE/wallets" -H "Authorization: Bearer $TOKEN" \
  -H "X-Merchant-Id: $MERCHANT" -H 'Content-Type: application/json' \
  -d '{"label":"Customer","kind":"CUSTOMER","currency":"INR","customerId":"customer_123"}'

curl --fail-with-body -sS "$BASE/wallets/$CUSTOMER_WALLET/funding" \
  -H "Authorization: Bearer $TOKEN" -H "X-Merchant-Id: $MERCHANT" \
  -H 'Idempotency-Key: demo-funding-001' -H 'Content-Type: application/json' \
  -d '{"amount":100000,"currency":"INR"}'

curl --fail-with-body -sS "$BASE/wallets" -H "Authorization: Bearer $TOKEN" \
  -H "X-Merchant-Id: $MERCHANT" -H 'Content-Type: application/json' \
  -d '{"label":"Settlement","kind":"SETTLEMENT","currency":"INR"}'

curl --fail-with-body -sS "$BASE/api-keys" -H "Authorization: Bearer $TOKEN" \
  -H "X-Merchant-Id: $MERCHANT" -H 'Content-Type: application/json' \
  -d '{"name":"Local integration","canWrite":true}'

# API_KEY is the one-time secret returned above; it fixes the tenant without a merchant header.
curl --fail-with-body -sS "$BASE/payments" -H "Authorization: Bearer $API_KEY" \
  -H 'Idempotency-Key: demo-payment-001' -H 'Content-Type: application/json' \
  -d '{"amount":49900,"currency":"INR","customerId":"customer_123","reference":"ORDER-98372","metadata":{"cartId":"cart_92"}}'

curl --fail-with-body -sS -X POST "$BASE/payments/$PAYMENT/confirm" \
  -H "Authorization: Bearer $API_KEY" -H 'Idempotency-Key: demo-confirm-001'
curl --fail-with-body -sS "$BASE/payments/$PAYMENT" -H "Authorization: Bearer $API_KEY"

curl --fail-with-body -sS "$BASE/payments/$PAYMENT/refunds" -H "Authorization: Bearer $API_KEY" \
  -H 'Idempotency-Key: demo-refund-001' -H 'Content-Type: application/json' \
  -d '{"amount":9900,"currency":"INR"}'
curl --fail-with-body -sS "$BASE/ledger/transactions" -H "Authorization: Bearer $API_KEY"

# URL must already be explicitly approved in WEBHOOK_ALLOWED_ORIGINS.
jq -n --arg url "$WEBHOOK_URL" '{url:$url,subscriptions:["payment.succeeded","refund.succeeded"]}' |
  curl --fail-with-body -sS "$BASE/webhooks" -H "Authorization: Bearer $TOKEN" \
  -H "X-Merchant-Id: $MERCHANT" -H 'Content-Type: application/json' --data-binary @-
curl --fail-with-body -sS "$BASE/webhook-deliveries" -H "Authorization: Bearer $TOKEN" -H "X-Merchant-Id: $MERCHANT"
```

Register the webhook before generating events you want to receive. Store its one-time signing secret privately. The sample Java receiver verifies signatures for local testing but does not implement durable receiver deduplication; it is not a production merchant receiver. See WEBHOOKS.md for exact signing bytes, retries and destination restrictions. Do not reuse the example idempotency keys for different request bodies.
