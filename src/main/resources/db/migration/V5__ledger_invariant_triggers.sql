CREATE OR REPLACE FUNCTION check_ledger_balance() RETURNS TRIGGER AS $$
DECLARE imbalance BIGINT;
BEGIN
    SELECT COALESCE(SUM(CASE WHEN entry_type = 'DEBIT' THEN amount ELSE -amount END), 0)
    INTO imbalance FROM ledger_entries WHERE transaction_id = NEW.transaction_id;
    IF imbalance <> 0 THEN
        RAISE EXCEPTION 'Ledger imbalance for transaction %: %', NEW.transaction_id, imbalance;
    END IF;
    RETURN NULL;
END; $$ LANGUAGE plpgsql;

CREATE CONSTRAINT TRIGGER trg_ledger_balance
    AFTER INSERT ON ledger_entries
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION check_ledger_balance();

CREATE OR REPLACE FUNCTION check_posting_invariant() RETURNS TRIGGER AS $$
DECLARE entry_count INT;
BEGIN
    IF NEW.status = 'POSTED' AND (OLD.status IS DISTINCT FROM 'POSTED') THEN
        SELECT COUNT(*) INTO entry_count FROM ledger_entries WHERE transaction_id = NEW.id;
        IF entry_count < 2 THEN
            RAISE EXCEPTION 'Transaction % marked POSTED with % ledger entries', NEW.id, entry_count;
        END IF;
    END IF;
    RETURN NEW;
END; $$ LANGUAGE plpgsql;

CREATE CONSTRAINT TRIGGER trg_posting_invariant
    AFTER UPDATE OF status ON transactions
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION check_posting_invariant();
