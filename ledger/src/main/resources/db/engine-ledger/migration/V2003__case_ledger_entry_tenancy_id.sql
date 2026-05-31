-- V2003: add tenancy_id to case_ledger_entry
-- Part of engine#299 multi-tenancy foundation. IF NOT EXISTS guards against consumer schemas
-- that pre-added this column. Nullable at DB level; NOT NULL enforced at entity level.

ALTER TABLE case_ledger_entry ADD COLUMN IF NOT EXISTS tenancy_id VARCHAR(64);

CREATE INDEX IF NOT EXISTS idx_case_ledger_entry_tenancy_id ON case_ledger_entry (tenancy_id);
