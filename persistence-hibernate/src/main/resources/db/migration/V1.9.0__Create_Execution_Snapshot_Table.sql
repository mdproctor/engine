CREATE TABLE IF NOT EXISTS execution_snapshot (
    case_id                   UUID         NOT NULL PRIMARY KEY,
    tenancy_id                VARCHAR(64)  NOT NULL,
    decomposition_snapshot    JSONB,
    dag_plan_snapshot          JSONB,
    dag_result_snapshot        JSONB,
    created_at                TIMESTAMP    NOT NULL,
    updated_at                TIMESTAMP    NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_execution_snapshot_tenancy ON execution_snapshot(tenancy_id);
