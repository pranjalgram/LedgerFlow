CREATE TABLE refund (
    id uuid PRIMARY KEY,
    merchant_id uuid NOT NULL,
    payment_id uuid NOT NULL,
    currency varchar(3) NOT NULL,
    amount_minor bigint NOT NULL CHECK (amount_minor BETWEEN 1 AND 9000000000000),
    status varchar(16) NOT NULL CHECK (status IN ('SUCCEEDED','FAILED')),
    failure_code varchar(80),
    ledger_transaction_id uuid UNIQUE,
    created_at timestamptz NOT NULL DEFAULT now(),
    CHECK ((status='SUCCEEDED')=(ledger_transaction_id IS NOT NULL)),
    FOREIGN KEY (merchant_id,payment_id,currency) REFERENCES payment(merchant_id,id,currency),
    FOREIGN KEY (merchant_id,ledger_transaction_id,currency) REFERENCES ledger_transaction(merchant_id,id,currency)
);
CREATE INDEX refunds_by_merchant ON refund(merchant_id,created_at DESC,id DESC);
CREATE INDEX refunds_by_payment ON refund(merchant_id,payment_id,status);
CREATE TRIGGER refund_immutable BEFORE UPDATE OR DELETE ON refund FOR EACH ROW EXECUTE FUNCTION reject_history_mutation();
CREATE TRIGGER refund_no_truncate BEFORE TRUNCATE ON refund FOR EACH STATEMENT EXECUTE FUNCTION reject_history_mutation();

CREATE FUNCTION require_refund_consistency() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE payment_id uuid;
DECLARE p public.payment%ROWTYPE;
DECLARE refunded numeric;
DECLARE payer uuid;
DECLARE payee uuid;
BEGIN
    IF TG_TABLE_NAME='payment' THEN payment_id := NEW.id; ELSE payment_id := NEW.payment_id; END IF;
    SELECT * INTO p FROM public.payment WHERE id=payment_id;
    SELECT coalesce(sum(r.amount_minor),0) INTO refunded FROM public.refund r WHERE r.payment_id=p.id AND r.status='SUCCEEDED';
    IF refunded <> p.refunded_minor OR refunded > p.amount_minor THEN
        RAISE EXCEPTION 'refund total mismatch' USING ERRCODE='23514';
    END IF;
    SELECT account_id INTO payer FROM public.wallet WHERE id=p.customer_wallet_id;
    SELECT account_id INTO payee FROM public.wallet WHERE id=p.settlement_wallet_id;
    IF EXISTS (
      SELECT 1 FROM public.refund r WHERE r.payment_id=p.id AND r.status='SUCCEEDED' AND (
        NOT EXISTS (SELECT 1 FROM public.ledger_transaction t WHERE t.id=r.ledger_transaction_id AND t.business_type='REFUND' AND t.business_id=r.id)
        OR (SELECT count(*) FROM public.ledger_entry e WHERE e.transaction_id=r.ledger_transaction_id)<>2
        OR NOT EXISTS (SELECT 1 FROM public.ledger_entry e WHERE e.transaction_id=r.ledger_transaction_id AND e.account_id=payee AND e.side='DEBIT' AND e.amount_minor=r.amount_minor)
        OR NOT EXISTS (SELECT 1 FROM public.ledger_entry e WHERE e.transaction_id=r.ledger_transaction_id AND e.account_id=payer AND e.side='CREDIT' AND e.amount_minor=r.amount_minor)
      )
    ) THEN RAISE EXCEPTION 'refund posting mismatch' USING ERRCODE='23514'; END IF;
    RETURN NULL;
END;
$$;
CREATE CONSTRAINT TRIGGER refund_consistency_on_refund AFTER INSERT ON refund
DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION require_refund_consistency();
CREATE CONSTRAINT TRIGGER refund_consistency_on_payment AFTER INSERT OR UPDATE ON payment
DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION require_refund_consistency();
GRANT SELECT,INSERT ON refund TO ledgerflow_runtime;
