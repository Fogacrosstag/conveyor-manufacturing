CREATE TABLE records (kind text NOT NULL, id text NOT NULL, body jsonb NOT NULL CHECK (jsonb_typeof(body)='object'), PRIMARY KEY(kind,id));
CREATE INDEX records_status ON records(kind,(body->>'status'));
CREATE INDEX records_order ON records(kind,(body->>'productionOrderId'));
CREATE TABLE outbox_events (sequence bigserial PRIMARY KEY,event_id text NOT NULL UNIQUE,aggregate_id text NOT NULL,topic text NOT NULL,payload jsonb NOT NULL,created_at timestamptz NOT NULL DEFAULT now(),published_at timestamptz,retry_count integer NOT NULL DEFAULT 0);
CREATE INDEX outbox_pending ON outbox_events(sequence) WHERE published_at IS NULL;
CREATE TABLE processed_events (event_id text NOT NULL,consumer_name text NOT NULL,processed_at timestamptz NOT NULL DEFAULT now(),PRIMARY KEY(event_id,consumer_name));
