CREATE TABLE transfer_decisions (
  reference uuid PRIMARY KEY,
  actor text NOT NULL,
  source_id uuid NOT NULL,
  destination_id uuid NOT NULL,
  amount text NOT NULL,
  currency text NOT NULL,
  ledger_operation_id uuid REFERENCES posting_operations(id),
  error_code text,
  created_at timestamptz NOT NULL DEFAULT now(),
  CHECK ((ledger_operation_id IS NOT NULL) <> (error_code IS NOT NULL))
);
CREATE TRIGGER immutable_transfer_decisions BEFORE UPDATE OR DELETE ON transfer_decisions
  FOR EACH ROW EXECUTE FUNCTION immutable_record();
