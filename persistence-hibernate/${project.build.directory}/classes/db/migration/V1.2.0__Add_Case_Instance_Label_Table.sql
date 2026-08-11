-- ElementCollection table for CaseInstance.labels (Set<String>)
-- Stores labels assigned to case instances by CaseLabelEvaluator (casehub-engine-queue).

CREATE TABLE IF NOT EXISTS case_instance_label (
    case_instance_id  BIGINT       NOT NULL,
    label             VARCHAR(255) NOT NULL,
    CONSTRAINT fk_case_instance_label_instance
        FOREIGN KEY (case_instance_id) REFERENCES case_instance(id)
);

CREATE INDEX IF NOT EXISTS idx_case_instance_label_instance ON case_instance_label(case_instance_id);
