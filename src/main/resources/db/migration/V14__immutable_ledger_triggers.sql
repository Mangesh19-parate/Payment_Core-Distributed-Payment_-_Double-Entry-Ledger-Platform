-- REQ-001 & REQ-103: Strict DB-level immutability enforcement for ledger_entries and audit_log
CREATE OR REPLACE FUNCTION prevent_table_mutation() RETURNS TRIGGER AS $$
BEGIN
    RAISE EXCEPTION 'Immutable invariant violation: UPDATE and DELETE are strictly forbidden on table %', TG_TABLE_NAME;
END;
$$ LANGUAGE plpgsql;

-- Enforce zero UPDATE/DELETE on ledger_entries
CREATE TRIGGER trg_ledger_entries_immutable
    BEFORE UPDATE OR DELETE ON ledger_entries
    FOR EACH ROW EXECUTE FUNCTION prevent_table_mutation();

-- Enforce zero UPDATE/DELETE on audit_log
CREATE TRIGGER trg_audit_log_immutable
    BEFORE UPDATE OR DELETE ON audit_log
    FOR EACH ROW EXECUTE FUNCTION prevent_table_mutation();
