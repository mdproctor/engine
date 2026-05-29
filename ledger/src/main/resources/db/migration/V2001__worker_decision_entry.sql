-- V2001: worker_decision_entry — per-worker capability decision records for trust scoring
-- Extends ledger_entry (JOINED inheritance). Written once per successful worker execution.

CREATE TABLE worker_decision_entry (
    id             UUID         NOT NULL,
    worker_id      VARCHAR(255) NOT NULL,
    capability_tag VARCHAR(255),
    case_id        UUID         NOT NULL,
    CONSTRAINT pk_worker_decision_entry PRIMARY KEY (id),
    CONSTRAINT fk_worker_decision_entry FOREIGN KEY (id) REFERENCES ledger_entry(id)
);

CREATE INDEX idx_wde_case_id      ON worker_decision_entry (case_id);
CREATE INDEX idx_wde_worker_id    ON worker_decision_entry (worker_id);
CREATE INDEX idx_wde_capability   ON worker_decision_entry (capability_tag);
