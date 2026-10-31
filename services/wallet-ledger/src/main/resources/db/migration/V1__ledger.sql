CREATE TABLE wallets (
 id uuid PRIMARY KEY, owner_id text NOT NULL UNIQUE, frozen boolean NOT NULL DEFAULT false,
 created_at timestamptz NOT NULL DEFAULT now()
);
CREATE TABLE ledger_accounts (
 id uuid PRIMARY KEY, wallet_id uuid UNIQUE REFERENCES wallets(id), system boolean NOT NULL DEFAULT false,
 CHECK ((system AND wallet_id IS NULL) OR (NOT system AND wallet_id IS NOT NULL))
);
CREATE TABLE account_balances (
 account_id uuid PRIMARY KEY REFERENCES ledger_accounts(id), amount bigint NOT NULL DEFAULT 0
);
CREATE TABLE posting_operations (
 id uuid PRIMARY KEY, actor text NOT NULL, idempotency_key varchar(200) NOT NULL,
 kind text NOT NULL CHECK(kind IN ('FUNDING','TRANSFER')), source_id uuid, destination_id uuid NOT NULL REFERENCES wallets(id),
 amount bigint NOT NULL CHECK(amount>0), currency text NOT NULL CHECK(currency='GBP'),
 created_at timestamptz NOT NULL DEFAULT now(), UNIQUE(actor,idempotency_key)
);
CREATE TABLE journals (
 id uuid PRIMARY KEY REFERENCES posting_operations(id), created_tx bigint NOT NULL DEFAULT txid_current(), currency text NOT NULL CHECK(currency='GBP'),
 created_at timestamptz NOT NULL DEFAULT now()
);
CREATE TABLE entries (
 journal_id uuid NOT NULL REFERENCES journals(id), account_id uuid NOT NULL REFERENCES ledger_accounts(id),
 amount bigint NOT NULL CHECK(amount<>0), PRIMARY KEY(journal_id,account_id)
);
CREATE TABLE outbox (
 event_id uuid PRIMARY KEY, event_type text NOT NULL, schema_version integer NOT NULL DEFAULT 1,
 occurred_at timestamptz NOT NULL DEFAULT now(), aggregate_id uuid NOT NULL,
 aggregate_version bigint NOT NULL DEFAULT 1, correlation_id text NOT NULL, payload jsonb NOT NULL,
 published_at timestamptz
);
CREATE INDEX pending_outbox ON outbox(occurred_at) WHERE published_at IS NULL;
CREATE FUNCTION immutable_record() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'Posted financial records are immutable'; END $$;
CREATE TRIGGER immutable_entries BEFORE UPDATE OR DELETE ON entries FOR EACH ROW EXECUTE FUNCTION immutable_record();
CREATE TRIGGER immutable_journals BEFORE UPDATE OR DELETE ON journals FOR EACH ROW EXECUTE FUNCTION immutable_record();
CREATE TRIGGER immutable_operations BEFORE UPDATE OR DELETE ON posting_operations FOR EACH ROW EXECUTE FUNCTION immutable_record();
CREATE FUNCTION balanced_journal() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF (SELECT count(*) FROM entries WHERE journal_id=NEW.id)<2 OR (SELECT sum(amount) FROM entries WHERE journal_id=NEW.id)<>0 THEN
 RAISE EXCEPTION 'Unbalanced journal'; END IF; RETURN NULL;
END $$;
CREATE CONSTRAINT TRIGGER balanced_journal AFTER INSERT ON journals DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION balanced_journal();
CREATE FUNCTION prevent_overdraft() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN
 IF NEW.amount<0 AND NOT (SELECT system FROM ledger_accounts WHERE id=NEW.account_id) THEN RAISE EXCEPTION 'Overdraft'; END IF; RETURN NEW;
END $$;
CREATE TRIGGER prevent_overdraft BEFORE INSERT OR UPDATE ON account_balances FOR EACH ROW EXECUTE FUNCTION prevent_overdraft();
INSERT INTO ledger_accounts(id,system) VALUES ('00000000-0000-0000-0000-000000000001',true);
INSERT INTO account_balances(account_id) VALUES ('00000000-0000-0000-0000-000000000001');

CREATE FUNCTION prevent_late_entries() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN
 IF (SELECT created_tx FROM journals WHERE id=NEW.journal_id) <> txid_current() THEN
 RAISE EXCEPTION 'Cannot append to a posted journal'; END IF; RETURN NEW;
END $$;
CREATE TRIGGER prevent_late_entries BEFORE INSERT ON entries FOR EACH ROW EXECUTE FUNCTION prevent_late_entries();
