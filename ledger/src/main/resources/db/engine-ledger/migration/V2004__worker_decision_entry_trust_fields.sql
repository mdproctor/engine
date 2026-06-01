-- V2004: add trust routing audit fields to worker_decision_entry (engine#403)
-- Null when trust routing was not active for this worker selection.

ALTER TABLE worker_decision_entry ADD COLUMN IF NOT EXISTS trust_score_at_routing DOUBLE PRECISION;
ALTER TABLE worker_decision_entry ADD COLUMN IF NOT EXISTS threshold_applied DOUBLE PRECISION;
