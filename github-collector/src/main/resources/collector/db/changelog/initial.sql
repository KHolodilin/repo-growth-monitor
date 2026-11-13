-- Collector database only. Logical relationships deliberately have no foreign keys.
CREATE TABLE collector_job_identity (
 id BIGSERIAL PRIMARY KEY, public_id UUID NOT NULL UNIQUE,
 origin_id VARCHAR(128) NOT NULL, external_job_id UUID NOT NULL,
 external_attempt_id UUID NOT NULL, payload_hash VARCHAR(64) NOT NULL,
 state VARCHAR(16) NOT NULL DEFAULT 'READY', accepted_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
 archived_at TIMESTAMPTZ, UNIQUE(origin_id, external_attempt_id)
);
CREATE TABLE collector_job (
 id BIGINT PRIMARY KEY, payload JSONB NOT NULL, destination_ref VARCHAR(128) NOT NULL,
 state VARCHAR(16) NOT NULL DEFAULT 'READY' CHECK(state IN ('READY','RETRY','RUNNING','SUCCEEDED','FAILED','CANCELLED')),
 execution_no INTEGER NOT NULL DEFAULT 0, generation BIGINT NOT NULL DEFAULT 0,
 locked_by VARCHAR(128), locked_until TIMESTAMPTZ, next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
 deadline_at TIMESTAMPTZ NOT NULL, updated_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
 CHECK ((state='RUNNING') = (locked_by IS NOT NULL AND locked_until IS NOT NULL))
);
CREATE INDEX collector_job_due ON collector_job(next_attempt_at,id) WHERE state IN ('READY','RETRY');
CREATE INDEX collector_job_expired ON collector_job(locked_until,id) WHERE state='RUNNING';
CREATE TABLE collector_execution (
 id BIGSERIAL PRIMARY KEY, job_id BIGINT NOT NULL, execution_no INTEGER NOT NULL,
 generation BIGINT NOT NULL, worker_id VARCHAR(128) NOT NULL, state VARCHAR(16) NOT NULL,
 started_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(), finished_at TIMESTAMPTZ,
 UNIQUE(job_id,execution_no)
);
CREATE TABLE collector_result (
 id BIGSERIAL PRIMARY KEY, public_id UUID NOT NULL UNIQUE, event_id UUID NOT NULL UNIQUE,
 job_id BIGINT NOT NULL UNIQUE, payload JSONB NOT NULL, payload_hash VARCHAR(64) NOT NULL,
 created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp()
);
CREATE TABLE collector_delivery (
 id BIGSERIAL PRIMARY KEY, result_id BIGINT NOT NULL UNIQUE, destination_ref VARCHAR(128) NOT NULL,
 state VARCHAR(16) NOT NULL DEFAULT 'READY' CHECK(state IN ('READY','RETRY','SENDING','DELIVERED')),
 generation BIGINT NOT NULL DEFAULT 0, locked_by VARCHAR(128), locked_until TIMESTAMPTZ,
 next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(), delivered_at TIMESTAMPTZ,
 CHECK ((state='SENDING') = (locked_by IS NOT NULL AND locked_until IS NOT NULL))
);
CREATE INDEX collector_delivery_due ON collector_delivery(next_attempt_at,id) WHERE state IN ('READY','RETRY');
CREATE INDEX collector_delivery_expired ON collector_delivery(locked_until,id) WHERE state='SENDING';
CREATE TABLE collector_job_history (
 archived_at TIMESTAMPTZ NOT NULL, job_id BIGINT NOT NULL, snapshot JSONB NOT NULL,
 PRIMARY KEY(archived_at,job_id)
) PARTITION BY RANGE(archived_at);
CREATE TABLE history_2027_02 PARTITION OF collector_job_history FOR VALUES FROM ('2027-02-01 00:00:00+00') TO ('2027-03-01 00:00:00+00');
CREATE TABLE history_2027_03 PARTITION OF collector_job_history FOR VALUES FROM ('2027-03-01 00:00:00+00') TO ('2027-04-01 00:00:00+00');
-- Canonical spring-boot-idempotency-starter JDBC schema. Business identity above outlives its TTL.
CREATE TABLE idempotency_records (
 operation VARCHAR(128) NOT NULL, idempotency_key VARCHAR(255) NOT NULL,
 request_hash VARCHAR(128) NOT NULL, status VARCHAR(32) NOT NULL,
 result_type VARCHAR(255), result_payload JSONB, error_code VARCHAR(128),
 created_at TIMESTAMPTZ NOT NULL, completed_at TIMESTAMPTZ, expires_at TIMESTAMPTZ,
 PRIMARY KEY(operation,idempotency_key)
);
CREATE INDEX idx_idempotency_records_expires_at ON idempotency_records(expires_at);
