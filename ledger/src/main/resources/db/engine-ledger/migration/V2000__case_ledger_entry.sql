-- V2000: case_ledger_entry — CaseHub audit ledger
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
