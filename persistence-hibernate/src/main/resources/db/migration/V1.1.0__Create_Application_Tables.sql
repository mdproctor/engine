-- Application tables for CaseHub engine
-- Sequences follow Hibernate 6 default naming: {table_name}_SEQ (lowercased by PostgreSQL)

CREATE SEQUENCE IF NOT EXISTS case_meta_model_seq START WITH 1 INCREMENT BY 50;
CREATE SEQUENCE IF NOT EXISTS case_instance_seq START WITH 1 INCREMENT BY 50;
CREATE SEQUENCE IF NOT EXISTS event_log_seq START WITH 1 INCREMENT BY 50;

-- Stores the case definition metadata (name, version, DSL, JSON schema)
CREATE TABLE IF NOT EXISTS case_meta_model (
                                               id           BIGINT       NOT NULL DEFAULT nextval('case_meta_model_seq'),
    name         VARCHAR(255) NOT NULL,
    namespace    VARCHAR(255),
    version      VARCHAR(50)  NOT NULL,
    title        VARCHAR(500),
    dsl          VARCHAR(50),
    definition   JSONB,
    created_at   TIMESTAMP    NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uq_case_meta_model_namespace_name_version
    UNIQUE (namespace, name, version)
    );

-- Represents a running case instance linked to a definition
CREATE TABLE IF NOT EXISTS case_instance (
                                             id                   BIGINT      NOT NULL DEFAULT nextval('case_instance_seq'),
    uuid                 UUID        NOT NULL,
    case_definition_id   BIGINT      NOT NULL,
    state                VARCHAR(50),
    parent_plan_item_id  UUID,
    parent_case_id       UUID,
    waiting_for_work_id VARCHAR(255),
    PRIMARY KEY (id),
    CONSTRAINT uq_case_instance_uuid
    UNIQUE (uuid),
    CONSTRAINT fk_case_instance_meta_model
    FOREIGN KEY (case_definition_id) REFERENCES case_meta_model(id)
    );

-- Append-only event log; seq is a monotonic identity column for ordering within a case stream
CREATE TABLE IF NOT EXISTS event_log (
                                         id           BIGINT       NOT NULL DEFAULT nextval('event_log_seq'),
    seq          BIGINT       GENERATED ALWAYS AS IDENTITY NOT NULL,
    case_id      UUID         NOT NULL,
    event_type   VARCHAR(255) NOT NULL,
    stream_type  VARCHAR(255) NOT NULL,
    worker_id    VARCHAR(255),
    timestamp    TIMESTAMP    NOT NULL,
    payload      JSONB,
    metadata     JSONB,
    PRIMARY KEY (id)
    );

CREATE INDEX IF NOT EXISTS idx_event_log_case_id ON event_log(case_id);
CREATE INDEX IF NOT EXISTS idx_event_log_case_worker ON event_log(case_id, worker_id);

-- Add subcase group tracking tables for multi-instance subcase management.
-- Tracks completion policies and child case relationships for subcase groups.

CREATE SEQUENCE IF NOT EXISTS subcase_group_seq START WITH 1 INCREMENT BY 50;

-- Main subcase group metadata table
CREATE TABLE IF NOT EXISTS subcase_group (
                                             id                      BIGINT       NOT NULL DEFAULT nextval('subcase_group_seq'),
    parent_case_id          UUID         NOT NULL,
    group_id                VARCHAR(255) NOT NULL,
    instance_count          INTEGER      NOT NULL,
    required_count          INTEGER      NOT NULL,
    completed_count         INTEGER      NOT NULL,
    rejected_count          INTEGER      NOT NULL,
    policy_triggered        BOOLEAN      NOT NULL,
    on_threshold_reached    VARCHAR(50)  NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uq_subcase_group_parent_group
    UNIQUE (parent_case_id, group_id)
    );

-- Child case IDs for each subcase group (ElementCollection mapping)
CREATE TABLE IF NOT EXISTS subcase_group_children (
                                                      group_entity_id  BIGINT NOT NULL,
                                                      child_case_id    UUID   NOT NULL,
                                                      CONSTRAINT fk_subcase_group_children_group
                                                      FOREIGN KEY (group_entity_id) REFERENCES subcase_group(id)
    );

CREATE INDEX IF NOT EXISTS idx_subcase_group_parent ON subcase_group(parent_case_id);
CREATE INDEX IF NOT EXISTS idx_subcase_group_children_group ON subcase_group_children(group_entity_id);

-- Add plan_item table for reactive PlanItemStore implementation
-- Sequence follows Hibernate 6 default naming: {table_name}_SEQ (lowercased by PostgreSQL)
CREATE SEQUENCE IF NOT EXISTS plan_item_seq START WITH 1 INCREMENT BY 50;

-- Stores plan item state and binding information
CREATE TABLE IF NOT EXISTS plan_item (
                                         id              BIGINT       NOT NULL DEFAULT nextval('plan_item_seq'),
    plan_item_id    VARCHAR(36)  NOT NULL,
    case_id         UUID         NOT NULL,
    binding_name    VARCHAR(255) NOT NULL,
    status          VARCHAR(50)  NOT NULL,
    created_at      TIMESTAMP    NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uq_plan_item_plan_item_id
    UNIQUE (plan_item_id)
    );

CREATE INDEX IF NOT EXISTS idx_plan_item_plan_item_id ON plan_item(plan_item_id);
CREATE INDEX IF NOT EXISTS idx_plan_item_case_id ON plan_item(case_id);