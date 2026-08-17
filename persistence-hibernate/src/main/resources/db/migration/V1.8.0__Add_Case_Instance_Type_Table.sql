-- ElementCollection table for CaseInstance.types (Set<String>)
-- Stores type paths assigned to case definitions for behavioral contract matching.

CREATE TABLE IF NOT EXISTS case_instance_type (
    case_instance_id  BIGINT       NOT NULL,
    type              VARCHAR(255) NOT NULL,
    CONSTRAINT fk_case_instance_type_instance
        FOREIGN KEY (case_instance_id) REFERENCES case_instance(id)
);

CREATE INDEX IF NOT EXISTS idx_case_instance_type_instance ON case_instance_type(case_instance_id);
