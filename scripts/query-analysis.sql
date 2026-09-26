\set ON_ERROR_STOP on
BEGIN READ ONLY;
SELECT merchant_id AS merchant FROM payment GROUP BY merchant_id ORDER BY count(*) DESC LIMIT 1 \gset
-- The real keyset-list access path; substitute your own tenant for independent analysis.
EXPLAIN (ANALYZE, BUFFERS)
SELECT id,status,amount_minor,created_at FROM payment
WHERE merchant_id=:'merchant'::uuid ORDER BY created_at DESC,id DESC LIMIT 51;
EXPLAIN (ANALYZE, BUFFERS)
SELECT id,status,amount_minor,created_at FROM payment
WHERE merchant_id=:'merchant'::uuid AND status='SUCCEEDED' ORDER BY created_at DESC,id DESC LIMIT 51;
-- CTE and aggregation: captured volume by UTC day, not a float conversion.
EXPLAIN (ANALYZE, BUFFERS)
WITH daily AS (
 SELECT (created_at AT TIME ZONE 'UTC')::date AS bucket_day, count(*) attempts,
 sum(amount_minor::numeric) FILTER (WHERE status IN ('SUCCEEDED','PARTIALLY_REFUNDED','REFUNDED')) captured
 FROM payment WHERE merchant_id=:'merchant'::uuid GROUP BY 1
) SELECT bucket_day,attempts,coalesce(captured,0) FROM daily ORDER BY bucket_day DESC;
-- Join and window: chronological account movement; liability credits increase balance.
EXPLAIN (ANALYZE, BUFFERS)
SELECT e.account_id,t.posted_at,e.ordinal,
 sum(CASE WHEN e.side=CASE a.kind WHEN 'ASSET' THEN 'DEBIT' ELSE 'CREDIT' END THEN e.amount_minor::numeric ELSE -e.amount_minor::numeric END)
 OVER (PARTITION BY e.account_id ORDER BY t.posted_at,t.id,e.ordinal ROWS UNBOUNDED PRECEDING) running_balance
FROM ledger_entry e JOIN ledger_transaction t ON t.id=e.transaction_id
JOIN ledger_account a ON a.id=e.account_id WHERE e.merchant_id=:'merchant'::uuid
ORDER BY e.account_id,t.posted_at,t.id,e.ordinal LIMIT 100;
ROLLBACK;
