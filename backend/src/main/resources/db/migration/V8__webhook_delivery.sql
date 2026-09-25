CREATE TABLE webhook_endpoint (
    id uuid PRIMARY KEY,
    merchant_id uuid NOT NULL REFERENCES merchant(id),
    url varchar(2048) NOT NULL,
    encrypted_secret text NOT NULL,
    subscriptions text[] NOT NULL CHECK (cardinality(subscriptions) BETWEEN 1 AND 30),
    enabled boolean NOT NULL DEFAULT true,
    created_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (merchant_id,id)
);
CREATE INDEX webhook_endpoint_tenant ON webhook_endpoint(merchant_id,created_at DESC,id DESC);
CREATE TABLE webhook_delivery (
    id uuid PRIMARY KEY,
    merchant_id uuid NOT NULL,
    endpoint_id uuid NOT NULL,
    event_id uuid NOT NULL,
    payload text NOT NULL CHECK (octet_length(payload)<=65536),
    state varchar(16) NOT NULL DEFAULT 'PENDING' CHECK (state IN ('PENDING','IN_FLIGHT','SUCCEEDED','FAILED')),
    attempts integer NOT NULL DEFAULT 0 CHECK (attempts>=0),
    cycle_attempts integer NOT NULL DEFAULT 0 CHECK (cycle_attempts>=0),
    next_attempt_at timestamptz NOT NULL DEFAULT now(),
    lease_token uuid,
    lease_until timestamptz,
    created_at timestamptz NOT NULL DEFAULT now(),
    completed_at timestamptz,
    last_http_status integer,
    last_error varchar(40),
    FOREIGN KEY (merchant_id,endpoint_id) REFERENCES webhook_endpoint(merchant_id,id),
    UNIQUE (endpoint_id,event_id),
    CHECK ((state='IN_FLIGHT') = (lease_token IS NOT NULL AND lease_until IS NOT NULL))
);
CREATE INDEX webhook_due ON webhook_delivery(next_attempt_at,id) WHERE state IN ('PENDING','IN_FLIGHT');
CREATE INDEX webhook_history ON webhook_delivery(merchant_id,created_at DESC,id DESC);
CREATE TABLE webhook_attempt (
    delivery_id uuid NOT NULL REFERENCES webhook_delivery(id),
    attempt integer NOT NULL,
    http_status integer,
    error_code varchar(40),
    duration_ms bigint NOT NULL CHECK (duration_ms>=0),
    completed_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (delivery_id,attempt)
);
CREATE TRIGGER webhook_attempt_immutable BEFORE UPDATE OR DELETE ON webhook_attempt
FOR EACH ROW EXECUTE FUNCTION reject_history_mutation();
CREATE TRIGGER webhook_attempt_no_truncate BEFORE TRUNCATE ON webhook_attempt
FOR EACH STATEMENT EXECUTE FUNCTION reject_history_mutation();
GRANT SELECT,INSERT ON webhook_endpoint,webhook_delivery,webhook_attempt TO ledgerflow_runtime;
GRANT UPDATE(enabled) ON webhook_endpoint TO ledgerflow_runtime;
GRANT UPDATE(state,attempts,cycle_attempts,next_attempt_at,lease_token,lease_until,completed_at,last_http_status,last_error) ON webhook_delivery TO ledgerflow_runtime;
