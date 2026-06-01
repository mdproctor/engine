-- Add tenancy_id to all core tables for multi-tenancy support.
-- Existing rows receive the sentinel value '__system__' (see ADR-0004).
-- Also adds target_type and output_mapping_expression to plan_item.

ALTER TABLE case_meta_model
    ADD COLUMN IF NOT EXISTS tenancy_id VARCHAR(64) NOT NULL DEFAULT '__system__';

ALTER TABLE case_instance
    ADD COLUMN IF NOT EXISTS tenancy_id VARCHAR(64) NOT NULL DEFAULT '__system__';

ALTER TABLE event_log
    ADD COLUMN IF NOT EXISTS tenancy_id VARCHAR(64) NOT NULL DEFAULT '__system__';

ALTER TABLE plan_item
    ADD COLUMN IF NOT EXISTS target_type              VARCHAR(20),
    ADD COLUMN IF NOT EXISTS output_mapping_expression VARCHAR(1000),
    ADD COLUMN IF NOT EXISTS tenancy_id               VARCHAR(64) NOT NULL DEFAULT '__system__';

ALTER TABLE subcase_group
    ADD COLUMN IF NOT EXISTS tenancy_id VARCHAR(64) NOT NULL DEFAULT '__system__';

-- Replace the old unique constraint on case_meta_model with the tenant-scoped one.
ALTER TABLE case_meta_model
    DROP CONSTRAINT IF EXISTS uq_case_meta_model_namespace_name_version;

ALTER TABLE case_meta_model
    ADD CONSTRAINT uq_case_meta_model_tenant_key
        UNIQUE (tenancy_id, namespace, name, version);

-- Indexes for tenancy_id columns.
CREATE INDEX IF NOT EXISTS idx_case_meta_model_tenancy_id ON case_meta_model (tenancy_id);
CREATE INDEX IF NOT EXISTS idx_case_instance_tenancy_id   ON case_instance   (tenancy_id);
CREATE INDEX IF NOT EXISTS idx_event_log_tenancy_id       ON event_log        (tenancy_id);
CREATE INDEX IF NOT EXISTS idx_plan_item_tenancy_id       ON plan_item        (tenancy_id);
CREATE INDEX IF NOT EXISTS idx_subcase_group_tenancy_id   ON subcase_group    (tenancy_id);
