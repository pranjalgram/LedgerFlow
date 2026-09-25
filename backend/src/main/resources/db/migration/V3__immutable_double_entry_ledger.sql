CREATE TABLE ledger_account (
    id uuid PRIMARY KEY,
    merchant_id uuid NOT NULL REFERENCES merchant(id),
    currency varchar(3) NOT NULL REFERENCES currency(code),
    kind varchar(12) NOT NULL CHECK (kind IN ('ASSET','LIABILITY')),
    purpose varchar(20) NOT NULL CHECK (purpose IN ('CASH_CONTROL','WALLET')),
    balance_minor bigint NOT NULL DEFAULT 0 CHECK (balance_minor >= 0),
    version bigint NOT NULL DEFAULT 0,
    active boolean NOT NULL DEFAULT true,
    created_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (merchant_id,id,currency),
    CHECK ((kind='ASSET' AND purpose='CASH_CONTROL') OR (kind='LIABILITY' AND purpose='WALLET'))
);
CREATE UNIQUE INDEX one_cash_control ON ledger_account(merchant_id,currency) WHERE purpose='CASH_CONTROL';
CREATE INDEX accounts_by_merchant ON ledger_account(merchant_id,id);

CREATE TABLE ledger_transaction (
    id uuid PRIMARY KEY,
    merchant_id uuid NOT NULL REFERENCES merchant(id),
    currency varchar(3) NOT NULL REFERENCES currency(code),
    business_type varchar(40) NOT NULL,
    business_id uuid NOT NULL,
    reverses_transaction_id uuid UNIQUE,
    posted_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    creation_xid xid8 NOT NULL DEFAULT pg_current_xact_id(),
    UNIQUE (merchant_id,id,currency),
    UNIQUE (merchant_id,business_type,business_id),
    FOREIGN KEY (merchant_id,reverses_transaction_id,currency) REFERENCES ledger_transaction(merchant_id,id,currency)
);
CREATE INDEX journals_by_merchant ON ledger_transaction(merchant_id,posted_at DESC,id DESC);

CREATE TABLE ledger_entry (
    id uuid PRIMARY KEY,
    merchant_id uuid NOT NULL,
    transaction_id uuid NOT NULL,
    account_id uuid NOT NULL,
    currency varchar(3) NOT NULL,
    side varchar(6) NOT NULL CHECK (side IN ('DEBIT','CREDIT')),
    amount_minor bigint NOT NULL CHECK (amount_minor BETWEEN 1 AND 9000000000000),
    ordinal integer NOT NULL CHECK (ordinal BETWEEN 1 AND 100),
    UNIQUE (transaction_id,ordinal),
    FOREIGN KEY (merchant_id,transaction_id,currency) REFERENCES ledger_transaction(merchant_id,id,currency),
    FOREIGN KEY (merchant_id,account_id,currency) REFERENCES ledger_account(merchant_id,id,currency)
);
CREATE INDEX entries_by_account ON ledger_entry(merchant_id,account_id,transaction_id);

CREATE TRIGGER journal_immutable BEFORE UPDATE OR DELETE ON ledger_transaction
FOR EACH ROW EXECUTE FUNCTION reject_history_mutation();
CREATE TRIGGER journal_no_truncate BEFORE TRUNCATE ON ledger_transaction
FOR EACH STATEMENT EXECUTE FUNCTION reject_history_mutation();
CREATE TRIGGER entry_immutable BEFORE UPDATE OR DELETE ON ledger_entry
FOR EACH ROW EXECUTE FUNCTION reject_history_mutation();
CREATE TRIGGER entry_no_truncate BEFORE TRUNCATE ON ledger_entry
FOR EACH STATEMENT EXECUTE FUNCTION reject_history_mutation();

CREATE FUNCTION guard_entry_append() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM public.ledger_transaction WHERE id=NEW.transaction_id AND creation_xid=pg_current_xact_id()) THEN
        RAISE EXCEPTION 'cannot append to a committed journal' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER entry_append_guard BEFORE INSERT ON ledger_entry
FOR EACH ROW EXECUTE FUNCTION guard_entry_append();

CREATE FUNCTION require_balanced_journal() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE journal_id uuid;
DECLARE entry_count bigint;
DECLARE account_count bigint;
DECLARE net numeric;
BEGIN
    IF TG_TABLE_NAME='ledger_transaction' THEN journal_id := NEW.id;
    ELSE journal_id := NEW.transaction_id;
    END IF;
    SELECT count(*),count(DISTINCT account_id),coalesce(sum(CASE side WHEN 'DEBIT' THEN amount_minor::numeric ELSE -amount_minor::numeric END),0)
      INTO entry_count,account_count,net FROM public.ledger_entry WHERE transaction_id=journal_id;
    IF entry_count < 2 OR entry_count > 100 OR account_count < 2 OR net <> 0 THEN
        RAISE EXCEPTION 'journal must contain balanced entries across distinct accounts' USING ERRCODE='23514';
    END IF;
    RETURN NULL;
END;
$$;
CREATE CONSTRAINT TRIGGER journal_balance_on_create AFTER INSERT ON ledger_transaction
DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION require_balanced_journal();
CREATE CONSTRAINT TRIGGER journal_balance_on_entry AFTER INSERT ON ledger_entry
DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION require_balanced_journal();

CREATE FUNCTION create_ledger_account(p_id uuid,p_merchant uuid,p_currency varchar,p_kind varchar,p_purpose varchar)
RETURNS uuid LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog,public,pg_temp AS $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM public.currency WHERE code=p_currency AND enabled) THEN
        RAISE EXCEPTION 'currency is not enabled' USING ERRCODE='23514';
    END IF;
    INSERT INTO public.ledger_account(id,merchant_id,currency,kind,purpose) VALUES (p_id,p_merchant,p_currency,p_kind,p_purpose);
    RETURN p_id;
END;
$$;

CREATE FUNCTION post_journal(p_id uuid,p_merchant uuid,p_currency varchar,p_business_type varchar,
    p_business_id uuid,p_entries jsonb,p_reverses uuid DEFAULT NULL)
RETURNS uuid LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog,public,pg_temp AS $$
DECLARE requested_count integer;
DECLARE distinct_accounts integer;
DECLARE matched_accounts integer;
DECLARE net numeric;
DECLARE valid boolean;
BEGIN
    IF jsonb_typeof(p_entries) IS DISTINCT FROM 'array' THEN
        RAISE EXCEPTION 'entries must be an array' USING ERRCODE='23514';
    END IF;
    SELECT count(*),count(DISTINCT account_id),
      sum(CASE side WHEN 'DEBIT' THEN amount::numeric ELSE -amount::numeric END),
      bool_and(account_id IS NOT NULL AND side IS NOT NULL AND side IN ('DEBIT','CREDIT')
        AND amount IS NOT NULL AND amount BETWEEN 1 AND 9000000000000)
      INTO requested_count,distinct_accounts,net,valid
      FROM jsonb_to_recordset(p_entries) AS e(account_id uuid,side text,amount bigint);
    IF requested_count NOT BETWEEN 2 AND 100 OR distinct_accounts < 2 OR net <> 0 OR valid IS DISTINCT FROM true THEN
        RAISE EXCEPTION 'invalid or unbalanced posting' USING ERRCODE='23514';
    END IF;
    PERFORM id FROM public.ledger_account
      WHERE id IN (SELECT account_id FROM jsonb_to_recordset(p_entries) AS e(account_id uuid))
      AND merchant_id=p_merchant AND currency=p_currency AND active ORDER BY id FOR UPDATE;
    GET DIAGNOSTICS matched_accounts = ROW_COUNT;
    IF matched_accounts <> distinct_accounts THEN
        RAISE EXCEPTION 'account tenant, currency or status mismatch' USING ERRCODE='23514';
    END IF;
    IF EXISTS (
      SELECT 1 FROM public.ledger_account a JOIN (
        SELECT account_id,sum(CASE side WHEN 'DEBIT' THEN amount::numeric ELSE -amount::numeric END) delta
        FROM jsonb_to_recordset(p_entries) AS e(account_id uuid,side text,amount bigint) GROUP BY account_id
      ) d ON a.id=d.account_id
      WHERE a.balance_minor::numeric + CASE a.kind WHEN 'ASSET' THEN d.delta ELSE -d.delta END < 0
    ) THEN RETURN NULL; END IF;
    IF p_reverses IS NOT NULL AND NOT EXISTS (
      SELECT 1 FROM public.ledger_transaction WHERE id=p_reverses AND merchant_id=p_merchant AND currency=p_currency
    ) THEN RAISE EXCEPTION 'invalid reversal reference' USING ERRCODE='23514'; END IF;
    IF p_reverses IS NOT NULL AND EXISTS (
      (SELECT account_id,side,sum(amount_minor)::numeric amount FROM public.ledger_entry WHERE transaction_id=p_reverses GROUP BY account_id,side
       EXCEPT SELECT account_id,CASE side WHEN 'DEBIT' THEN 'CREDIT' ELSE 'DEBIT' END,sum(amount)::numeric
       FROM jsonb_to_recordset(p_entries) AS e(account_id uuid,side text,amount bigint) GROUP BY account_id,side)
      UNION ALL
      (SELECT account_id,CASE side WHEN 'DEBIT' THEN 'CREDIT' ELSE 'DEBIT' END,sum(amount)::numeric
       FROM jsonb_to_recordset(p_entries) AS e(account_id uuid,side text,amount bigint) GROUP BY account_id,side
       EXCEPT SELECT account_id,side,sum(amount_minor)::numeric FROM public.ledger_entry WHERE transaction_id=p_reverses GROUP BY account_id,side)
    ) THEN RAISE EXCEPTION 'reversal must invert all original entries' USING ERRCODE='23514'; END IF;
    INSERT INTO public.ledger_transaction(id,merchant_id,currency,business_type,business_id,reverses_transaction_id)
      VALUES (p_id,p_merchant,p_currency,p_business_type,p_business_id,p_reverses);
    INSERT INTO public.ledger_entry(id,merchant_id,transaction_id,account_id,currency,side,amount_minor,ordinal)
      SELECT gen_random_uuid(),p_merchant,p_id,(e->>'account_id')::uuid,p_currency,e->>'side',(e->>'amount')::bigint,n::integer
      FROM jsonb_array_elements(p_entries) WITH ORDINALITY AS items(e,n);
    UPDATE public.ledger_account a SET
      balance_minor=(a.balance_minor::numeric + CASE a.kind WHEN 'ASSET' THEN d.delta ELSE -d.delta END)::bigint,
      version=a.version+1
      FROM (SELECT account_id,sum(CASE side WHEN 'DEBIT' THEN amount::numeric ELSE -amount::numeric END) delta
        FROM jsonb_to_recordset(p_entries) AS e(account_id uuid,side text,amount bigint) GROUP BY account_id) d
      WHERE a.id=d.account_id;
    RETURN p_id;
END;
$$;

-- Bootstrap creates the non-login runtime role; the application login inherits it.
REVOKE CREATE ON SCHEMA public FROM PUBLIC;
GRANT USAGE ON SCHEMA public TO ledgerflow_ledger_owner;
GRANT SELECT ON currency,merchant,ledger_account,ledger_transaction,ledger_entry TO ledgerflow_ledger_owner;
GRANT INSERT ON ledger_account,ledger_transaction,ledger_entry TO ledgerflow_ledger_owner;
GRANT UPDATE (balance_minor,version) ON ledger_account TO ledgerflow_ledger_owner;
ALTER FUNCTION create_ledger_account(uuid,uuid,varchar,varchar,varchar) OWNER TO ledgerflow_ledger_owner;
ALTER FUNCTION post_journal(uuid,uuid,varchar,varchar,uuid,jsonb,uuid) OWNER TO ledgerflow_ledger_owner;
GRANT USAGE ON SCHEMA public TO ledgerflow_runtime;
GRANT SELECT ON ALL TABLES IN SCHEMA public TO ledgerflow_runtime;
GRANT INSERT,UPDATE,DELETE ON app_user,merchant,merchant_membership,refresh_session TO ledgerflow_runtime;
GRANT INSERT ON audit_event TO ledgerflow_runtime;
REVOKE ALL ON FUNCTION create_ledger_account(uuid,uuid,varchar,varchar,varchar) FROM PUBLIC;
REVOKE ALL ON FUNCTION post_journal(uuid,uuid,varchar,varchar,uuid,jsonb,uuid) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION create_ledger_account(uuid,uuid,varchar,varchar,varchar) TO ledgerflow_runtime;
GRANT EXECUTE ON FUNCTION post_journal(uuid,uuid,varchar,varchar,uuid,jsonb,uuid) TO ledgerflow_runtime;
