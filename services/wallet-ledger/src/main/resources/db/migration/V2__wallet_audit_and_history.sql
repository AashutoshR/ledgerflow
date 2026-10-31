CREATE TABLE wallet_audit (
    id uuid PRIMARY KEY,
    wallet_id uuid NOT NULL REFERENCES wallets(id),
    actor text NOT NULL,
    action text NOT NULL CHECK (action IN ('FREEZE', 'UNFREEZE')),
    reason varchar(500) NOT NULL CHECK (length(trim(reason)) > 0),
    correlation_id text NOT NULL,
    occurred_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX wallet_audit_history ON wallet_audit(wallet_id, occurred_at DESC, id DESC);
CREATE INDEX wallet_entry_history ON entries(account_id, journal_id);
CREATE INDEX journal_history ON journals(created_at DESC, id DESC);
CREATE TRIGGER immutable_wallet_audit BEFORE UPDATE OR DELETE ON wallet_audit
    FOR EACH ROW EXECUTE FUNCTION immutable_record();
