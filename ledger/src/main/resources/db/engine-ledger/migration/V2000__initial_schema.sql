-- V2000: CaseHub engine ledger — initial schema
-- Extends ledger_entry (JOINED inheritance). V1000–V1004 are reserved by quarkus-ledger.

CREATE TABLE case_ledger_entry (
    id           UUID         NOT NULL,
    case_id      UUID         NOT NULL,
    command_type VARCHAR(100),
    event_type   VARCHAR(100),
    case_status  VARCHAR(50),
    CONSTRAINT pk_case_ledger_entry PRIMARY KEY (id),
    CONSTRAINT fk_case_ledger_entry FOREIGN KEY (id) REFERENCES ledger_entry(id)
);

CREATE INDEX idx_cle_case_id ON case_ledger_entry (case_id);

CREATE TABLE worker_decision_entry (
    id                       UUID             NOT NULL,
    worker_id                VARCHAR(255)     NOT NULL,
    capability_tag           VARCHAR(255),
    case_id                  UUID             NOT NULL,
    trust_score_at_routing   DOUBLE PRECISION,
    threshold_applied        DOUBLE PRECISION,
    routing_rationale        TEXT,
    CONSTRAINT pk_worker_decision_entry PRIMARY KEY (id),
    CONSTRAINT fk_worker_decision_entry FOREIGN KEY (id) REFERENCES ledger_entry(id)
);

CREATE INDEX idx_wde_case_id      ON worker_decision_entry (case_id);
CREATE INDEX idx_wde_worker_id    ON worker_decision_entry (worker_id);
CREATE INDEX idx_wde_capability   ON worker_decision_entry (capability_tag);
