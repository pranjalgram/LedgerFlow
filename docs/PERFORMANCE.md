# Performance experiments

`load-tests/financial.js` is a real k6 workload: registration creates an isolated tenant, two wallets and simulated funding; iterations read balances/history, transfer funds, and create/capture payments. All commands have fresh idempotency keys. Money stays integer paise. Setup aborts if the API is not ready or demo funding is disabled. No request/secret payloads are printed.

```sh
docker run --rm -v "$PWD/load-tests:/scripts:ro" -e DURATION=30s -e VUS=1 grafana/k6:2.3.0 run /scripts/financial.js
```

On native Linux add `--add-host host.docker.internal:host-gateway`, or supply BASE_URL for your target. Default one VU with a one-second pause stays within the per-principal budget; deliberately increasing VUS or removing the pause can measure throttling. Do not filter 429/503 from the error rate. For an isolated capacity experiment disable rate limiting explicitly and record that choice, sampling, resources and database size. Never disable public rate limiting to obtain a flattering benchmark.

Output includes requests/sec, mean, p50, p95, p99 and error rate. Thresholds require less than 1% HTTP failures and greater than 99% successful business checks; no latency SLO is asserted. Tokens last ten minutes; keep runs shorter or add a deliberate refresh workload before running longer tests. Every VU in one run shares a wallet to expose hot-account contention. Separate tenants/wallets are needed for a comparison with uniform contention. Test data is retained for inspection; use a disposable database for repeated runs.

## PostgreSQL plans

Run `scripts/query-analysis.sql` through psql against a demo database containing payments. It opens a read-only transaction, chooses an existing tenant and executes EXPLAIN (ANALYZE, BUFFERS) for keyset-list, status-filter, daily CTE aggregation and a joined window-function running balance. The query uses the same liability/asset normal-side semantics as the ledger. Posting timestamps plus UUID/ordinal are a deterministic presentation order, not a global commit sequence.

`docs/measurements/query-plans.txt` is actual output from the local PostgreSQL 18.6 Docker database on 2026-09-26. It contains only a small E2E dataset and warm-buffer measurements. It demonstrates selected indexes, join/sort/window plans and buffer use; it is not evidence of production performance. At this size PostgreSQL can legitimately choose a different merchant-prefixed index or a sequential scan. No force-index setting or speculative index was added to manufacture a result. Repeat with realistic tenant skew and retained history before changing indexes. Financial mutation locking remains the sorted account locks in post_journal; EXPLAIN does not prove concurrency safety—the integration tests do.

Record CPU, available memory, Docker limits, OS, Java/PostgreSQL versions, fixture cardinality, rate-limit/tracing settings, warmup and exact command with any published measurements. No capacity claim follows from a short smoke run.

An 8-second, one-VU smoke run passed all 10 business checks with no HTTP failures against the packaged demo API on 2026-09-26. This validates the script, not capacity; no throughput/latency benchmark is published from that short run.
