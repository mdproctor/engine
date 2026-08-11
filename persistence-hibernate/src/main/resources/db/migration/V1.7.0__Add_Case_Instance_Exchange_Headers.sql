-- Add exchange_headers JSONB column for Exchange header coordination (engine#633)
ALTER TABLE case_instance ADD COLUMN IF NOT EXISTS exchange_headers jsonb;
