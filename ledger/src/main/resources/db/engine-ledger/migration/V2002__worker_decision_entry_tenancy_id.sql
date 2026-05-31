-- V2002: add tenancy_id to worker_decision_entry
-- Part of engine#299 multi-tenancy foundation. IF NOT EXISTS guards against consumer schemas
-- that pre-added this column (e.g. casehub-aml V2005). Nullable at DB level because
-- consumer installs may have existing rows; NOT NULL is enforced at entity level.

ALTER TABLE worker_decision_entry ADD COLUMN IF NOT EXISTS tenancy_id VARCHAR(64);

CREATE INDEX IF NOT EXISTS idx_worker_decision_entry_tenancy_id ON worker_decision_entry (tenancy_id);
