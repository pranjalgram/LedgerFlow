CREATE TABLE wallet (
    id uuid PRIMARY KEY,
    merchant_id uuid NOT NULL REFERENCES merchant(id),
    account_id uuid NOT NULL UNIQUE,
    currency varchar(3) NOT NULL,
    label varchar(120) NOT NULL,
    kind varchar(20) NOT NULL CHECK (kind IN ('CUSTOMER','SETTLEMENT')),
    customer_id varchar(120),
    created_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (merchant_id,id,currency),
    FOREIGN KEY (merchant_id,account_id,currency) REFERENCES ledger_account(merchant_id,id,currency),
    CHECK (kind='CUSTOMER' OR customer_id IS NULL)
);
CREATE UNIQUE INDEX settlement_per_currency ON wallet(merchant_id,currency) WHERE kind='SETTLEMENT';
CREATE UNIQUE INDEX customer_wallet_mapping ON wallet(merchant_id,customer_id,currency) WHERE customer_id IS NOT NULL;
CREATE INDEX wallets_by_merchant ON wallet(merchant_id,created_at DESC,id DESC);

CREATE TABLE funding (
    id uuid PRIMARY KEY,
    merchant_id uuid NOT NULL,
    wallet_id uuid NOT NULL,
    currency varchar(3) NOT NULL,
    amount_minor bigint NOT NULL CHECK (amount_minor BETWEEN 1 AND 9000000000000),
    ledger_transaction_id uuid NOT NULL UNIQUE,
    created_at timestamptz NOT NULL DEFAULT now(),
    FOREIGN KEY (merchant_id,wallet_id,currency) REFERENCES wallet(merchant_id,id,currency),
    FOREIGN KEY (merchant_id,ledger_transaction_id,currency) REFERENCES ledger_transaction(merchant_id,id,currency)
);

CREATE TABLE transfer (
    id uuid PRIMARY KEY,
    merchant_id uuid NOT NULL,
    source_wallet_id uuid NOT NULL,
    destination_wallet_id uuid NOT NULL,
    currency varchar(3) NOT NULL,
    amount_minor bigint NOT NULL CHECK (amount_minor BETWEEN 1 AND 9000000000000),
    ledger_transaction_id uuid NOT NULL UNIQUE,
    created_at timestamptz NOT NULL DEFAULT now(),
    CHECK (source_wallet_id<>destination_wallet_id),
    FOREIGN KEY (merchant_id,source_wallet_id,currency) REFERENCES wallet(merchant_id,id,currency),
    FOREIGN KEY (merchant_id,destination_wallet_id,currency) REFERENCES wallet(merchant_id,id,currency),
    FOREIGN KEY (merchant_id,ledger_transaction_id,currency) REFERENCES ledger_transaction(merchant_id,id,currency)
);
CREATE INDEX transfers_by_merchant ON transfer(merchant_id,created_at DESC,id DESC);

CREATE TABLE idempotency_record (
    merchant_id uuid NOT NULL REFERENCES merchant(id),
    operation varchar(180) NOT NULL,
    key varchar(128) COLLATE "C" NOT NULL,
    fingerprint char(64) NOT NULL,
    fingerprint_version smallint NOT NULL DEFAULT 1,
    status_code integer CHECK (status_code BETWEEN 200 AND 499),
    response_json jsonb,
    location varchar(240),
    created_at timestamptz NOT NULL DEFAULT now(),
    response_expires_at timestamptz NOT NULL DEFAULT now()+interval '30 days',
    PRIMARY KEY (merchant_id,operation,key),
    CHECK (octet_length(response_json::text) <= 65536)
);
CREATE FUNCTION require_idempotency_completion() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF EXISTS (SELECT 1 FROM public.idempotency_record WHERE merchant_id=NEW.merchant_id
        AND operation=NEW.operation AND key=NEW.key AND status_code IS NULL) THEN
        RAISE EXCEPTION 'idempotency claim must complete in its transaction' USING ERRCODE='23514';
    END IF;
    RETURN NULL;
END;
$$;
CREATE CONSTRAINT TRIGGER idempotency_completion AFTER INSERT ON idempotency_record
DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION require_idempotency_completion();

CREATE TABLE outbox_event (
    id uuid PRIMARY KEY,
    merchant_id uuid NOT NULL REFERENCES merchant(id),
    aggregate_type varchar(40) NOT NULL,
    aggregate_id uuid NOT NULL,
    aggregate_version bigint NOT NULL CHECK (aggregate_version>0),
    event_index integer NOT NULL DEFAULT 0,
    event_type varchar(100) NOT NULL,
    payload jsonb NOT NULL CHECK (octet_length(payload::text)<=65536),
    trace_context varchar(512),
    created_at timestamptz NOT NULL DEFAULT now(),
    state varchar(16) NOT NULL DEFAULT 'PENDING' CHECK (state IN ('PENDING','IN_FLIGHT','PUBLISHED','BLOCKED')),
    attempts integer NOT NULL DEFAULT 0,
    next_attempt_at timestamptz NOT NULL DEFAULT now(),
    lease_token uuid,
    lease_until timestamptz,
    published_at timestamptz,
    last_error_code varchar(100),
    UNIQUE (aggregate_type,aggregate_id,aggregate_version,event_index),
    UNIQUE (merchant_id,id)
);
CREATE INDEX outbox_due ON outbox_event(next_attempt_at,created_at,id) WHERE published_at IS NULL;
CREATE FUNCTION protect_event_envelope() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF (NEW.id,NEW.merchant_id,NEW.aggregate_type,NEW.aggregate_id,NEW.aggregate_version,NEW.event_index,NEW.event_type,NEW.payload,NEW.trace_context,NEW.created_at)
       IS DISTINCT FROM
       (OLD.id,OLD.merchant_id,OLD.aggregate_type,OLD.aggregate_id,OLD.aggregate_version,OLD.event_index,OLD.event_type,OLD.payload,OLD.trace_context,OLD.created_at) THEN
        RAISE EXCEPTION 'event envelope is immutable' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER outbox_immutable_envelope BEFORE UPDATE ON outbox_event FOR EACH ROW EXECUTE FUNCTION protect_event_envelope();
CREATE TRIGGER funding_immutable BEFORE UPDATE OR DELETE ON funding FOR EACH ROW EXECUTE FUNCTION reject_history_mutation();
CREATE TRIGGER transfer_immutable BEFORE UPDATE OR DELETE ON transfer FOR EACH ROW EXECUTE FUNCTION reject_history_mutation();

GRANT SELECT,INSERT ON wallet,funding,transfer,idempotency_record,outbox_event TO ledgerflow_runtime;
GRANT UPDATE(status_code,response_json,location) ON idempotency_record TO ledgerflow_runtime;
GRANT UPDATE(state,attempts,next_attempt_at,lease_token,lease_until,published_at,last_error_code) ON outbox_event TO ledgerflow_runtime;
