CREATE TABLE processed_event (
    consumer_name varchar(80) NOT NULL,
    event_id uuid NOT NULL,
    processed_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (consumer_name,event_id)
);
CREATE TABLE notification (
    id uuid PRIMARY KEY,
    merchant_id uuid NOT NULL REFERENCES merchant(id),
    event_id uuid NOT NULL,
    kind varchar(100) NOT NULL,
    resource_id uuid NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    read_at timestamptz,
    UNIQUE (merchant_id,event_id)
);
CREATE INDEX notifications_by_merchant ON notification(merchant_id,created_at DESC,id DESC);
CREATE TABLE dead_letter (
    id uuid PRIMARY KEY,
    merchant_id uuid REFERENCES merchant(id),
    event_id uuid,
    source_topic varchar(120) NOT NULL,
    source_partition integer NOT NULL,
    source_offset bigint NOT NULL,
    payload text NOT NULL CHECK (octet_length(payload)<=65536),
    received_at timestamptz NOT NULL DEFAULT now(),
    replayed_at timestamptz,
    UNIQUE (source_topic,source_partition,source_offset)
);
GRANT SELECT,INSERT ON processed_event,notification,dead_letter TO ledgerflow_runtime;
GRANT UPDATE(read_at) ON notification TO ledgerflow_runtime;
GRANT UPDATE(replayed_at) ON dead_letter TO ledgerflow_runtime;
