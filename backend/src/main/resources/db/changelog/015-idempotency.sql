-- Canonical spring-boot-idempotency-starter 1.2.0 JDBC schema; owned by Liquibase.
CREATE TABLE idempotency_records (
 operation VARCHAR(128) NOT NULL, idempotency_key VARCHAR(255) NOT NULL,
 request_hash VARCHAR(128) NOT NULL, status VARCHAR(32) NOT NULL,
 result_type VARCHAR(255), result_payload JSONB, error_code VARCHAR(128),
 created_at TIMESTAMPTZ NOT NULL, completed_at TIMESTAMPTZ, expires_at TIMESTAMPTZ,
 PRIMARY KEY(operation,idempotency_key)
);
CREATE INDEX idx_idempotency_records_expires_at ON idempotency_records(expires_at);
-- Permanent business identity binding, not an HTTP replay store. Retains aliases for coalesced requests.
CREATE TABLE collection_request_command (
 command_key VARCHAR(64) PRIMARY KEY, actor_id BIGINT NOT NULL, repository_id BIGINT NOT NULL,
 request_id BIGINT NOT NULL, coalesced BOOLEAN NOT NULL, accepted_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp()
);
CREATE INDEX collection_command_request ON collection_request_command(request_id);
