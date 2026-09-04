-- Persist pending action gate for restart resilience (engine#433)
ALTER TABLE case_instance ADD COLUMN IF NOT EXISTS pending_action_gate jsonb;
