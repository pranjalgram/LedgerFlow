CREATE TABLE api_key (
    id uuid PRIMARY KEY,
    merchant_id uuid NOT NULL REFERENCES merchant(id),
    name varchar(80) NOT NULL,
    prefix varchar(24) NOT NULL UNIQUE,
    secret_hash char(64) NOT NULL,
    can_write boolean NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    last_used_at timestamptz,
    revoked_at timestamptz
);
CREATE INDEX api_keys_by_merchant ON api_key(merchant_id,created_at DESC,id DESC);

CREATE TABLE payment (
    id uuid PRIMARY KEY,
    merchant_id uuid NOT NULL,
    customer_wallet_id uuid NOT NULL,
    settlement_wallet_id uuid NOT NULL,
    currency varchar(3) NOT NULL,
    amount_minor bigint NOT NULL CHECK (amount_minor BETWEEN 1 AND 9000000000000),
    refunded_minor bigint NOT NULL DEFAULT 0 CHECK (refunded_minor>=0 AND refunded_minor<=amount_minor),
    reference varchar(120) NOT NULL,
    metadata jsonb NOT NULL DEFAULT '{}' CHECK (octet_length(metadata::text)<=16384),
    status varchar(24) NOT NULL CHECK (status IN ('CREATED','PROCESSING','SUCCEEDED','FAILED','CANCELLED','PARTIALLY_REFUNDED','REFUNDED')),
    failure_code varchar(80),
    ledger_transaction_id uuid UNIQUE,
    version bigint NOT NULL DEFAULT 1,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (merchant_id,id,currency),
    CHECK (customer_wallet_id<>settlement_wallet_id),
    CHECK ((status IN ('SUCCEEDED','PARTIALLY_REFUNDED','REFUNDED'))=(ledger_transaction_id IS NOT NULL)),
    CHECK ((status='REFUNDED' AND refunded_minor=amount_minor) OR
           (status='PARTIALLY_REFUNDED' AND refunded_minor>0 AND refunded_minor<amount_minor) OR
           (status NOT IN ('REFUNDED','PARTIALLY_REFUNDED') AND refunded_minor=0)),
    FOREIGN KEY (merchant_id,customer_wallet_id,currency) REFERENCES wallet(merchant_id,id,currency),
    FOREIGN KEY (merchant_id,settlement_wallet_id,currency) REFERENCES wallet(merchant_id,id,currency),
    FOREIGN KEY (merchant_id,ledger_transaction_id,currency) REFERENCES ledger_transaction(merchant_id,id,currency)
);
CREATE INDEX payments_by_merchant ON payment(merchant_id,created_at DESC,id DESC);
CREATE INDEX payments_by_status ON payment(merchant_id,status,created_at DESC,id DESC);
CREATE INDEX payments_by_reference ON payment(merchant_id,reference);

CREATE TABLE payment_transition (
    id uuid PRIMARY KEY,
    merchant_id uuid NOT NULL,
    payment_id uuid NOT NULL,
    currency varchar(3) NOT NULL,
    from_state varchar(24),
    to_state varchar(24) NOT NULL,
    aggregate_version bigint NOT NULL,
    occurred_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    reason_code varchar(80),
    UNIQUE (payment_id,aggregate_version),
    FOREIGN KEY (merchant_id,payment_id,currency) REFERENCES payment(merchant_id,id,currency)
);
CREATE TRIGGER payment_transition_immutable BEFORE UPDATE OR DELETE ON payment_transition
FOR EACH ROW EXECUTE FUNCTION reject_history_mutation();

CREATE FUNCTION guard_payment_transition() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF (NEW.id,NEW.merchant_id,NEW.customer_wallet_id,NEW.settlement_wallet_id,NEW.currency,NEW.amount_minor,NEW.reference,NEW.metadata,NEW.created_at)
       IS DISTINCT FROM (OLD.id,OLD.merchant_id,OLD.customer_wallet_id,OLD.settlement_wallet_id,OLD.currency,OLD.amount_minor,OLD.reference,OLD.metadata,OLD.created_at)
       OR NEW.version<>OLD.version+1 OR (OLD.ledger_transaction_id IS NOT NULL AND NEW.ledger_transaction_id IS DISTINCT FROM OLD.ledger_transaction_id)
       OR NOT ((OLD.status='CREATED' AND NEW.status IN ('PROCESSING','CANCELLED'))
         OR (OLD.status='PROCESSING' AND NEW.status IN ('SUCCEEDED','FAILED'))
         OR (OLD.status IN ('SUCCEEDED','PARTIALLY_REFUNDED') AND NEW.status IN ('PARTIALLY_REFUNDED','REFUNDED') AND NEW.refunded_minor>OLD.refunded_minor)) THEN
        RAISE EXCEPTION 'invalid payment transition' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER payment_state_guard BEFORE UPDATE ON payment FOR EACH ROW EXECUTE FUNCTION guard_payment_transition();

CREATE FUNCTION require_payment_posting() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE p public.payment%ROWTYPE;
DECLARE payer uuid;
DECLARE payee uuid;
BEGIN
    SELECT * INTO p FROM public.payment WHERE id=NEW.id;
    IF p.status='PROCESSING' THEN RAISE EXCEPTION 'synchronous payment cannot commit in PROCESSING' USING ERRCODE='23514'; END IF;
    IF p.ledger_transaction_id IS NOT NULL THEN
        SELECT account_id INTO payer FROM public.wallet WHERE id=p.customer_wallet_id;
        SELECT account_id INTO payee FROM public.wallet WHERE id=p.settlement_wallet_id;
        IF NOT EXISTS (SELECT 1 FROM public.ledger_transaction WHERE id=p.ledger_transaction_id AND business_type='PAYMENT' AND business_id=p.id)
           OR (SELECT count(*) FROM public.ledger_entry WHERE transaction_id=p.ledger_transaction_id)<>2
           OR NOT EXISTS (SELECT 1 FROM public.ledger_entry WHERE transaction_id=p.ledger_transaction_id AND account_id=payer AND side='DEBIT' AND amount_minor=p.amount_minor)
           OR NOT EXISTS (SELECT 1 FROM public.ledger_entry WHERE transaction_id=p.ledger_transaction_id AND account_id=payee AND side='CREDIT' AND amount_minor=p.amount_minor) THEN
            RAISE EXCEPTION 'payment posting mismatch' USING ERRCODE='23514';
        END IF;
    END IF;
    RETURN NULL;
END;
$$;
CREATE CONSTRAINT TRIGGER payment_posting_guard AFTER INSERT OR UPDATE ON payment
DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION require_payment_posting();

GRANT SELECT,INSERT,UPDATE ON api_key,payment TO ledgerflow_runtime;
GRANT SELECT,INSERT ON payment_transition TO ledgerflow_runtime;
