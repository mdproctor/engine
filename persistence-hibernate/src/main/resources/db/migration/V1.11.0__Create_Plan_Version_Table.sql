CREATE TABLE IF NOT EXISTS plan_version (
    id                UUID         NOT NULL PRIMARY KEY,
    case_id           UUID         NOT NULL,
    version           INT          NOT NULL,
    tenancy_id        VARCHAR(64)  NOT NULL,
    timestamp         TIMESTAMP    NOT NULL,
    trigger_data      JSONB,
    snapshot_data     JSONB,
    delta_data        JSONB,
    CONSTRAINT uq_plan_version_case_version UNIQUE (case_id, version)
);

CREATE INDEX IF NOT EXISTS idx_plan_version_case_id ON plan_version(case_id);
CREATE INDEX IF NOT EXISTS idx_plan_version_tenancy ON plan_version(tenancy_id);
