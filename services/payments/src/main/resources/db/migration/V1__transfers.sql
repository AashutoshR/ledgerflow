CREATE TABLE transfers (
  id uuid PRIMARY KEY,
  actor text NOT NULL,
  idempotency_key varchar(200) NOT NULL,
  source_id uuid NOT NULL,
  destination_id uuid NOT NULL,
  amount bigint NOT NULL CHECK(amount>0),
  currency text NOT NULL CHECK(currency='GBP'),
  status text NOT NULL CHECK(status IN ('CREATED','PROCESSING','SUCCEEDED','FAILED')),
  ledger_operation_id uuid,
  error_code text,
  correlation_id text NOT NULL,
  attempts integer NOT NULL DEFAULT 0,
  next_attempt_at timestamptz NOT NULL DEFAULT now(),
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE(actor,idempotency_key),
  CHECK(source_id<>destination_id)
);
CREATE INDEX pending_transfers ON transfers(next_attempt_at) WHERE status IN ('CREATED','PROCESSING');
CREATE TABLE workflow_attempts (
  id uuid PRIMARY KEY,
  transfer_id uuid NOT NULL REFERENCES transfers(id),
  outcome text NOT NULL,
  occurred_at timestamptz NOT NULL DEFAULT now()
);
CREATE TABLE outbox (
  event_id uuid PRIMARY KEY, event_type text NOT NULL, schema_version integer NOT NULL DEFAULT 1,
  occurred_at timestamptz NOT NULL DEFAULT now(), aggregate_id uuid NOT NULL,
  aggregate_version bigint NOT NULL DEFAULT 1, correlation_id text NOT NULL, payload jsonb NOT NULL,
  published_at timestamptz
);
