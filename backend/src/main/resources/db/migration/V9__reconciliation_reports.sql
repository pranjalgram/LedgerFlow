CREATE TABLE reconciliation_run (
    id uuid PRIMARY KEY,
    merchant_id uuid NOT NULL REFERENCES merchant(id),
    status varchar(24) NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING','RUNNING','PASSED','DISCREPANCIES','FAILED')),
    created_at timestamptz NOT NULL DEFAULT now(),
    started_at timestamptz,
    completed_at timestamptz,
    snapshot_at timestamptz,
    records_processed bigint NOT NULL DEFAULT 0 CHECK (records_processed>=0),
    discrepancies bigint NOT NULL DEFAULT 0 CHECK (discrepancies>=0),
    duration_ms bigint,
    attempts integer NOT NULL DEFAULT 0,
    lease_token uuid,
    lease_until timestamptz,
    error_code varchar(40),
    UNIQUE (merchant_id,id)
);
CREATE UNIQUE INDEX one_active_reconciliation ON reconciliation_run(merchant_id) WHERE status IN ('PENDING','RUNNING');
CREATE INDEX reconciliation_tenant_history ON reconciliation_run(merchant_id,created_at DESC,id DESC);
CREATE TABLE reconciliation_discrepancy (
    id uuid PRIMARY KEY,
    merchant_id uuid NOT NULL,
    run_id uuid NOT NULL,
    code varchar(40) NOT NULL,
    resource_id uuid NOT NULL,
    expected text NOT NULL,
    actual text NOT NULL,
    FOREIGN KEY (merchant_id,run_id) REFERENCES reconciliation_run(merchant_id,id),
    UNIQUE (run_id,code,resource_id)
);
CREATE INDEX reconciliation_findings_by_run ON reconciliation_discrepancy(run_id,id);
CREATE TRIGGER reconciliation_discrepancy_immutable BEFORE UPDATE OR DELETE ON reconciliation_discrepancy
FOR EACH ROW EXECUTE FUNCTION reject_history_mutation();
CREATE TRIGGER reconciliation_discrepancy_no_truncate BEFORE TRUNCATE ON reconciliation_discrepancy
FOR EACH STATEMENT EXECUTE FUNCTION reject_history_mutation();
CREATE FUNCTION guard_completed_reconciliation() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF OLD.status IN ('PASSED','DISCREPANCIES','FAILED') THEN
        RAISE EXCEPTION 'completed reconciliation is immutable' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER reconciliation_completed_immutable BEFORE UPDATE ON reconciliation_run
FOR EACH ROW EXECUTE FUNCTION guard_completed_reconciliation();
GRANT SELECT,INSERT,UPDATE ON reconciliation_run TO ledgerflow_runtime;
GRANT SELECT,INSERT ON reconciliation_discrepancy TO ledgerflow_runtime;

-- Read-only cross-module audit contract. The calling operation uses one repeatable-read snapshot.
CREATE FUNCTION reconciliation_findings(p_merchant uuid)
RETURNS TABLE(code text,resource_id uuid,expected text,actual text)
LANGUAGE sql STABLE AS $$
WITH journal_totals AS (
    SELECT t.id,count(e.id) AS entries,count(DISTINCT e.account_id) AS accounts,
        coalesce(sum(CASE e.side WHEN 'DEBIT' THEN e.amount_minor::numeric ELSE -e.amount_minor::numeric END),0) AS net
    FROM ledger_transaction t LEFT JOIN ledger_entry e ON e.transaction_id=t.id
    WHERE t.merchant_id=p_merchant GROUP BY t.id
), account_totals AS (
    SELECT a.id,a.balance_minor,coalesce(sum(CASE WHEN (a.kind='ASSET' AND e.side='DEBIT') OR (a.kind='LIABILITY' AND e.side='CREDIT')
        THEN e.amount_minor::numeric ELSE -e.amount_minor::numeric END),0) AS derived
    FROM ledger_account a LEFT JOIN ledger_entry e ON e.account_id=a.id
    WHERE a.merchant_id=p_merchant GROUP BY a.id
), expected_postings AS (
    SELECT p.id,p.merchant_id,p.currency,p.amount_minor,p.ledger_transaction_id,'PAYMENT' AS kind,c.account_id AS debit,s.account_id AS credit
    FROM payment p JOIN wallet c ON c.id=p.customer_wallet_id JOIN wallet s ON s.id=p.settlement_wallet_id
    WHERE p.merchant_id=p_merchant AND p.status IN ('SUCCEEDED','PARTIALLY_REFUNDED','REFUNDED')
    UNION ALL
    SELECT r.id,r.merchant_id,r.currency,r.amount_minor,r.ledger_transaction_id,'REFUND',s.account_id,c.account_id
    FROM refund r JOIN payment p ON p.id=r.payment_id JOIN wallet c ON c.id=p.customer_wallet_id JOIN wallet s ON s.id=p.settlement_wallet_id
    WHERE r.merchant_id=p_merchant AND r.status='SUCCEEDED'
    UNION ALL
    SELECT t.id,t.merchant_id,t.currency,t.amount_minor,t.ledger_transaction_id,'TRANSFER',s.account_id,d.account_id
    FROM transfer t JOIN wallet s ON s.id=t.source_wallet_id JOIN wallet d ON d.id=t.destination_wallet_id WHERE t.merchant_id=p_merchant
    UNION ALL
    SELECT f.id,f.merchant_id,f.currency,f.amount_minor,f.ledger_transaction_id,'FUNDING',a.id,w.account_id
    FROM funding f JOIN wallet w ON w.id=f.wallet_id
    JOIN ledger_account a ON a.merchant_id=f.merchant_id AND a.currency=f.currency AND a.purpose='CASH_CONTROL' WHERE f.merchant_id=p_merchant
)
SELECT 'JOURNAL_UNBALANCED',id,'2..100 entries, >=2 accounts, net=0',concat('entries=',entries,', accounts=',accounts,', net=',net)
FROM journal_totals WHERE entries<2 OR entries>100 OR accounts<2 OR net<>0
UNION ALL
SELECT 'BALANCE_MISMATCH',id,derived::text,balance_minor::text FROM account_totals WHERE derived<>balance_minor
UNION ALL
SELECT 'POSTING_MISMATCH',p.id,'exact '||p.kind||' debit/credit posting','missing or inconsistent journal'
FROM expected_postings p LEFT JOIN ledger_transaction t ON t.id=p.ledger_transaction_id
WHERE t.id IS NULL OR (t.merchant_id,t.currency,t.business_type,t.business_id) IS DISTINCT FROM (p.merchant_id,p.currency,p.kind,p.id)
    OR (SELECT count(*) FROM ledger_entry e WHERE e.transaction_id=t.id)<>2
    OR NOT EXISTS(SELECT 1 FROM ledger_entry e WHERE e.transaction_id=t.id AND e.account_id=p.debit AND e.side='DEBIT' AND e.amount_minor=p.amount_minor)
    OR NOT EXISTS(SELECT 1 FROM ledger_entry e WHERE e.transaction_id=t.id AND e.account_id=p.credit AND e.side='CREDIT' AND e.amount_minor=p.amount_minor)
UNION ALL
SELECT 'REFUND_TOTAL_MISMATCH',p.id,coalesce(sum(r.amount_minor),0)::text,p.refunded_minor::text
FROM payment p LEFT JOIN refund r ON r.payment_id=p.id AND r.status='SUCCEEDED' WHERE p.merchant_id=p_merchant
GROUP BY p.id HAVING coalesce(sum(r.amount_minor),0)<>p.refunded_minor OR coalesce(sum(r.amount_minor),0)>p.amount_minor
UNION ALL
SELECT 'ORPHAN_JOURNAL',t.id,'corresponding financial resource',t.business_type
FROM ledger_transaction t WHERE t.merchant_id=p_merchant AND (
    (t.business_type='PAYMENT' AND NOT EXISTS(SELECT 1 FROM payment p WHERE p.id=t.business_id AND p.ledger_transaction_id=t.id)) OR
    (t.business_type='REFUND' AND NOT EXISTS(SELECT 1 FROM refund r WHERE r.id=t.business_id AND r.ledger_transaction_id=t.id)) OR
    (t.business_type='TRANSFER' AND NOT EXISTS(SELECT 1 FROM transfer x WHERE x.id=t.business_id AND x.ledger_transaction_id=t.id)) OR
    (t.business_type='FUNDING' AND NOT EXISTS(SELECT 1 FROM funding f WHERE f.id=t.business_id AND f.ledger_transaction_id=t.id)))
UNION ALL
SELECT 'DUPLICATE_BUSINESS_POSTING',business_id,'one journal per business reference',count(*)::text
FROM ledger_transaction WHERE merchant_id=p_merchant GROUP BY business_type,business_id HAVING count(*)>1
UNION ALL
SELECT 'ORPHAN_OR_MISSCOPED_ENTRY',e.id,'matching account/journal tenant and currency','invalid reference'
FROM ledger_entry e LEFT JOIN ledger_account a ON a.id=e.account_id LEFT JOIN ledger_transaction t ON t.id=e.transaction_id
WHERE e.merchant_id=p_merchant AND (a.id IS NULL OR t.id IS NULL OR (e.merchant_id,e.currency) IS DISTINCT FROM (a.merchant_id,a.currency)
    OR (e.merchant_id,e.currency) IS DISTINCT FROM (t.merchant_id,t.currency));
$$;
REVOKE ALL ON FUNCTION reconciliation_findings(uuid) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION reconciliation_findings(uuid) TO ledgerflow_runtime;
