-- V2001: worker_decision_entry — per-worker capability decision records for trust scoring
-- Extends ledger_entry (JOINED inheritance). Written once per successful worker execution.

CREATE TABLE worker_decision_entry (
    id                       UUID             NOT NULL,
    worker_id                VARCHAR(255)     NOT NULL,
    capability_tag           VARCHAR(255),
    case_id                  UUID             NOT NULL,
    tenancy_id               VARCHAR(64)      NOT NULL DEFAULT '__system__',
    trust_score_at_routing   DOUBLE PRECISION,
    threshold_applied        DOUBLE PRECISION,
    CONSTRAINT pk_worker_decision_entry PRIMARY KEY (id),
    CONSTRAINT fk_worker_decision_entry FOREIGN KEY (id) REFERENCES ledger_entry(id)
);

CREATE INDEX idx_wde_case_id      ON worker_decision_entry (case_id);
CREATE INDEX idx_wde_worker_id    ON worker_decision_entry (worker_id);
CREATE INDEX idx_wde_capability   ON worker_decision_entry (capability_tag);
CREATE INDEX idx_worker_decision_entry_tenancy_id ON worker_decision_entry (tenancy_id);
