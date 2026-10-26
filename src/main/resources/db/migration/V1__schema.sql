-- loan-processing-service system of record.
-- Timestamps use the database clock. Audit rows are append-only via trigger.

CREATE TABLE loan_application (
    id uuid PRIMARY KEY,
    version bigint NOT NULL,
    status text NOT NULL,
    status_reason text,
    customer_principal text NOT NULL,
    wrapped_dek bytea NOT NULL,
    encryption_key_version int NOT NULL,
    first_name_enc bytea NOT NULL,
    last_name_enc bytea NOT NULL,
    ssn_enc bytea NOT NULL,
    ssn_last4 text NOT NULL,
    ssn_hmac bytea NOT NULL,
    ssn_hmac_previous bytea,
    dob_enc bytea NOT NULL,
    email_enc bytea NOT NULL,
    phone_enc bytea NOT NULL,
    address_enc bytea NOT NULL,
    correlation_id uuid NOT NULL,
    lease_until timestamptz,
    lease_generation bigint NOT NULL DEFAULT 0,
    attempt_credit int NOT NULL DEFAULT 0,
    attempt_extraction int NOT NULL DEFAULT 0,
    credit_attempt_budget int NOT NULL,
    extraction_attempt_budget int NOT NULL,
    withdraw_requested boolean NOT NULL DEFAULT false,
    last_activity_at timestamptz NOT NULL DEFAULT now(),
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT loan_application_status_chk CHECK (status IN (
        'RECEIVED', 'CREDIT_IN_PROGRESS', 'CREDIT_FAILED', 'PAYSLIP_REVIEW',
        'EXTRACTION_QUEUED', 'EXTRACTING', 'EXTRACTION_FAILED', 'MANUAL_HOLD',
        'APPROVED', 'DECLINED', 'ABANDONED', 'WITHDRAWN'
    )),
    CONSTRAINT ssn_last4_chk CHECK (ssn_last4 ~ '^[0-9]{4}$')
);

CREATE INDEX loan_application_status_activity_idx ON loan_application (status, last_activity_at);
CREATE INDEX loan_application_ssn_hmac_idx ON loan_application (ssn_hmac);
CREATE INDEX loan_application_principal_created_idx ON loan_application (customer_principal, created_at);

CREATE TABLE credit_inquiry (
    id uuid PRIMARY KEY,
    application_id uuid NOT NULL REFERENCES loan_application (id),
    provider text NOT NULL,
    provider_request_id text NOT NULL UNIQUE,
    status text NOT NULL,
    attempts int NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT credit_inquiry_status_chk CHECK (status IN ('PENDING', 'COMPLETED', 'FAILED')),
    CONSTRAINT credit_inquiry_provider_chk CHECK (provider IN ('STUB', 'EXPERIAN'))
);

CREATE TABLE credit_report (
    id uuid PRIMARY KEY,
    application_id uuid NOT NULL UNIQUE REFERENCES loan_application (id),
    inquiry_id uuid NOT NULL REFERENCES credit_inquiry (id),
    provider text NOT NULL,
    score int NOT NULL,
    score_scale text NOT NULL,
    cutoff_enabled boolean NOT NULL,
    cutoff_minimum int,
    cutoff_comparison text,
    raw_payload_enc bytea,
    pulled_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE document (
    id uuid PRIMARY KEY,
    application_id uuid NOT NULL REFERENCES loan_application (id),
    kind text NOT NULL,
    status text NOT NULL,
    reject_reason text,
    bucket text,
    object_key text,
    media_type text,
    byte_size bigint,
    sha256 text,
    page_count int,
    malware_scan text,
    uploaded_by text NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT document_kind_chk CHECK (kind IN ('PAYSLIP')),
    CONSTRAINT document_status_chk CHECK (status IN ('RECEIVED', 'REJECTED', 'SUPERSEDED')),
    CONSTRAINT document_scan_chk CHECK (malware_scan IS NULL OR malware_scan IN ('CLEAN', 'INFECTED', 'FAILED')),
    CONSTRAINT document_reject_chk CHECK (reject_reason IS NULL OR reject_reason IN (
        'MALWARE', 'ENCRYPTED', 'CORRUPT', 'UNREADABLE', 'OBJECT_MISSING', 'TYPE', 'OVERSIZE'
    ))
);

CREATE UNIQUE INDEX document_sha_active_idx ON document (application_id, sha256)
    WHERE status <> 'REJECTED' AND sha256 IS NOT NULL;

CREATE TABLE extraction_run (
    id uuid PRIMARY KEY,
    application_id uuid NOT NULL REFERENCES loan_application (id),
    lease_generation bigint NOT NULL,
    disposition text,
    prompt_version int,
    model text,
    provider text,
    config_generation bigint,
    config_snapshot jsonb,
    started_at timestamptz NOT NULL DEFAULT now(),
    finished_at timestamptz,
    error_code text,
    input_sha256 text,
    raw_output_enc bytea,
    CONSTRAINT extraction_disposition_chk CHECK (disposition IS NULL OR disposition IN ('APPLIED', 'DISCARDED', 'FAILED'))
);

CREATE TABLE extraction_field (
    id uuid PRIMARY KEY,
    extraction_run_id uuid NOT NULL REFERENCES extraction_run (id),
    document_id uuid NOT NULL REFERENCES document (id),
    identity_result text,
    identity_detail jsonb,
    salary_monthly numeric(14, 2),
    salary_currency text,
    cadence text,
    gross_amount numeric(14, 2),
    salary_confidence numeric(6, 4),
    grounding_passed boolean,
    uncertain_reasons text[],
    CONSTRAINT identity_result_chk CHECK (identity_result IS NULL OR identity_result IN ('MATCH', 'MISMATCH', 'UNCERTAIN'))
);

CREATE TABLE decision (
    id uuid PRIMARY KEY,
    application_id uuid NOT NULL REFERENCES loan_application (id),
    extraction_run_id uuid REFERENCES extraction_run (id),
    source text NOT NULL,
    outcome text NOT NULL,
    rule_code text NOT NULL,
    secondary_rule_codes text[],
    salary_compared numeric(14, 2),
    threshold numeric(14, 2),
    comparison text,
    config_generation bigint,
    actor_id text,
    comment_enc bytea,
    created_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT decision_source_chk CHECK (source IN ('AUTO', 'OPERATOR')),
    CONSTRAINT decision_outcome_chk CHECK (outcome IN ('APPROVED', 'DECLINED', 'MANUAL_HOLD'))
);

CREATE UNIQUE INDEX decision_one_auto_per_run ON decision (extraction_run_id)
    WHERE source = 'AUTO' AND extraction_run_id IS NOT NULL;

CREATE UNIQUE INDEX decision_one_terminal_auto ON decision (application_id)
    WHERE source = 'AUTO' AND outcome IN ('APPROVED', 'DECLINED');

CREATE TABLE application_status_history (
    id uuid PRIMARY KEY,
    application_id uuid NOT NULL REFERENCES loan_application (id),
    from_status text,
    to_status text NOT NULL,
    reason_code text,
    actor_type text NOT NULL,
    actor_id text,
    correlation_id uuid,
    occurred_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE outbox (
    id uuid PRIMARY KEY,
    aggregate_id uuid,
    event_type text NOT NULL,
    payload jsonb NOT NULL,
    correlation_id uuid,
    created_at timestamptz NOT NULL DEFAULT now(),
    published_at timestamptz,
    attempts int NOT NULL DEFAULT 0,
    last_error text,
    next_attempt_at timestamptz,
    CONSTRAINT outbox_event_chk CHECK (event_type IN ('ApplicationSubmitted', 'ExtractionRequested'))
);

CREATE INDEX outbox_unpublished_idx ON outbox (created_at)
    WHERE published_at IS NULL;

CREATE TABLE outbox_dead_letter (
    id uuid PRIMARY KEY,
    aggregate_id uuid,
    event_type text NOT NULL,
    payload jsonb NOT NULL,
    correlation_id uuid,
    created_at timestamptz NOT NULL,
    attempts int NOT NULL,
    last_error text,
    dead_lettered_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE idempotency_record (
    customer_principal text NOT NULL,
    route text NOT NULL,
    idempotency_key text NOT NULL,
    request_hash text NOT NULL,
    state text NOT NULL,
    http_status int,
    response_body jsonb,
    created_at timestamptz NOT NULL DEFAULT now(),
    expires_at timestamptz NOT NULL,
    PRIMARY KEY (customer_principal, route, idempotency_key),
    CONSTRAINT idempotency_state_chk CHECK (state IN ('IN_PROGRESS', 'COMPLETED'))
);

CREATE TABLE runtime_config (
    key text PRIMARY KEY,
    value_json jsonb NOT NULL,
    value_type text NOT NULL,
    version bigint NOT NULL,
    updated_at timestamptz NOT NULL DEFAULT now(),
    updated_by text NOT NULL
);

CREATE TABLE config_generation (
    singleton boolean PRIMARY KEY DEFAULT true CHECK (singleton),
    generation bigint NOT NULL
);

INSERT INTO config_generation (singleton, generation) VALUES (true, 1);

CREATE TABLE config_change_proposal (
    id uuid PRIMARY KEY,
    config_key text NOT NULL,
    value_json jsonb NOT NULL,
    reason text NOT NULL,
    proposed_by text NOT NULL,
    approved_by text,
    status text NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    expires_at timestamptz NOT NULL,
    correlation_id uuid,
    CONSTRAINT proposal_status_chk CHECK (status IN ('PENDING', 'PUBLISHED', 'REJECTED', 'EXPIRED'))
);

CREATE TABLE config_change_audit (
    id uuid PRIMARY KEY,
    config_key text NOT NULL,
    old_value jsonb,
    new_value jsonb,
    proposed_by text NOT NULL,
    approved_by text NOT NULL,
    reason text NOT NULL,
    correlation_id uuid,
    generation bigint NOT NULL,
    occurred_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE audit_event (
    event_id uuid PRIMARY KEY,
    occurred_at timestamptz NOT NULL DEFAULT now(),
    correlation_id uuid,
    request_id text,
    causation_id uuid,
    trace_id text,
    actor_type text NOT NULL,
    actor_id text,
    action text NOT NULL,
    resource_type text,
    resource_id text,
    outcome text NOT NULL,
    reason_code text,
    metadata jsonb,
    prev_hash bytea,
    event_hash bytea NOT NULL,
    CONSTRAINT audit_outcome_chk CHECK (outcome IN ('SUCCESS', 'FAILURE'))
);

CREATE INDEX audit_event_application_idx ON audit_event (resource_type, resource_id, occurred_at);
CREATE INDEX audit_event_correlation_idx ON audit_event (correlation_id);

CREATE OR REPLACE FUNCTION forbid_audit_mutation() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'audit_event is append-only';
END;
$$;

CREATE TRIGGER audit_event_no_update
    BEFORE UPDATE OR DELETE ON audit_event
    FOR EACH ROW EXECUTE FUNCTION forbid_audit_mutation();

REVOKE UPDATE, DELETE ON audit_event FROM PUBLIC;

CREATE TABLE scheduler_lease (
    name text PRIMARY KEY,
    owner text NOT NULL,
    lease_until timestamptz NOT NULL
);

CREATE TABLE pending_object (
    object_key text PRIMARY KEY,
    application_id uuid NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE integrity_report (
    id uuid PRIMARY KEY,
    ran_at timestamptz NOT NULL DEFAULT now(),
    violation_count int NOT NULL,
    sample_ids jsonb NOT NULL
);
