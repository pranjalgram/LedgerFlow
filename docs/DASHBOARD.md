# Merchant dashboard

The React dashboard uses the real authenticated APIs for overview totals, wallets/funding/history, transfers, payment lifecycle/metadata, refunds, ledger/accounts, API keys, webhook delivery attempts/replay, reconciliation, asynchronous backlog, audit history and merchant memberships.

Start PostgreSQL and the backend with the `demo` profile for funding, then run `npm ci && npm run dev` in `frontend/`. Vite proxies `/api` and readiness to localhost:8080. Register a workspace, create CUSTOMER and SETTLEMENT wallets, fund the customer wallet, then create a transfer or payment. Wallet UUIDs are visible in their URLs/list. Payments must be explicitly confirmed before refunding. Amount inputs are integer **paise**, not rupees.

Dashboard JWT/refresh tokens stay in memory; no browser storage or ambient cookies. Refresh rotates after eight minutes; failure returns to sign-in. Reloading intentionally requires login. Merchant selection clears query state and remounts the workspace so previous-tenant data/forms cannot remain visible. Authorization remains server-side; UI role checks only disable unavailable actions. Secrets are component-local, never cached in TanStack Query, and disappear when dismissed or the page is left.

TanStack Query handles server reads/invalidation; React Hook Form and Zod validate commands. Financial forms preserve the request's idempotency key after an ambiguous failure. A changed payload gets a new key; successful completion clears the attempt. Navigation/reload does not persist an unresolved key, so after an ambiguous result followed by navigation users should inspect history before issuing another command. Integrations should retain their keys across restarts as documented in IDEMPOTENCY.md.

Money formatting uses BigInt division and remainder, including aggregate balances larger than JavaScript's safe integer range. Per-command request amounts are converted to Number only after integer/range validation (maximum 9,000,000,000,000, below the safe integer limit). The chart visualizes **payment counts**, never floating-point money.

Lists support cursor pagination where the backend does. Payment filtering uses exact reference and status. The ledger account list and membership list currently return full collections; API keys currently list the newest 100. These are documented limits pending broader paging. Creation forms use UUIDs rather than an unbounded wallet dropdown. The backend must be started with demo funding enabled; otherwise the funding API returns an error, not simulated UI success.

## Verification

`npm run lint`, `npm run typecheck`, `npm test`, and `npm run build` verify the frontend. `npx playwright install chromium && npm run test:e2e` requires a real demo backend on port 8080 and starts Vite itself. The browser flow registers an isolated merchant, creates/funds wallets, transfers, inspects balanced entries, captures a payment, issues partial and full refunds, creates/revokes a secret, runs reconciliation, checks desktop/mobile layout, and signs out/in. Financial responses are not intercepted or mocked. RTL verifies readiness/error behavior, exact large-money formatting, validation and key reuse after failed requests.

The chart is loaded separately from authentication and the rest of the dashboard. Keyboard-visible focus, explicit form labels, status/error messages, responsive navigation and horizontally scrollable tables are provided. A full accessibility audit and cross-browser/mobile-device certification have not been performed.
