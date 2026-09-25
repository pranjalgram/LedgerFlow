CREATE TABLE app_user (
    id uuid PRIMARY KEY,
    normalized_email varchar(254) NOT NULL UNIQUE,
    password_hash varchar(255) NOT NULL,
    enabled boolean NOT NULL DEFAULT true,
    created_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE merchant (
    id uuid PRIMARY KEY,
    display_name varchar(120) NOT NULL,
    status varchar(20) NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE','SUSPENDED')),
    created_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE merchant_membership (
    merchant_id uuid NOT NULL REFERENCES merchant(id),
    user_id uuid NOT NULL REFERENCES app_user(id),
    role varchar(20) NOT NULL CHECK (role IN ('OWNER','ADMIN','DEVELOPER','VIEWER')),
    created_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (merchant_id,user_id)
);
CREATE INDEX membership_by_user ON merchant_membership(user_id,merchant_id);

CREATE TABLE refresh_session (
    id uuid PRIMARY KEY,
    user_id uuid NOT NULL REFERENCES app_user(id),
    token_hash char(64) NOT NULL UNIQUE,
    family_id uuid NOT NULL,
    expires_at timestamptz NOT NULL,
    consumed_at timestamptz,
    revoked_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX refresh_family ON refresh_session(family_id);

CREATE TABLE audit_event (
    id uuid PRIMARY KEY,
    merchant_id uuid REFERENCES merchant(id),
    actor_id uuid,
    action varchar(80) NOT NULL,
    resource_id uuid,
    correlation_id varchar(36),
    occurred_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX audit_tenant_history ON audit_event(merchant_id,occurred_at DESC,id DESC);

CREATE FUNCTION reject_history_mutation() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'history is immutable' USING ERRCODE = '23514';
END;
$$;
CREATE TRIGGER audit_immutable BEFORE UPDATE OR DELETE ON audit_event
FOR EACH ROW EXECUTE FUNCTION reject_history_mutation();
CREATE TRIGGER audit_no_truncate BEFORE TRUNCATE ON audit_event
FOR EACH STATEMENT EXECUTE FUNCTION reject_history_mutation();
