-- Product database only. Keys are supplied externally; no plaintext GitHub token columns.
CREATE TABLE github_credential (
 id BIGSERIAL PRIMARY KEY, ref VARCHAR(128) NOT NULL UNIQUE, current_version BIGINT NOT NULL,
 revoked BOOLEAN NOT NULL DEFAULT false, updated_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp()
);
CREATE TABLE github_credential_version (
 id BIGSERIAL PRIMARY KEY, credential_id BIGINT NOT NULL, version BIGINT NOT NULL,
 key_id VARCHAR(64) NOT NULL, nonce BYTEA NOT NULL, ciphertext BYTEA NOT NULL,
 expires_at TIMESTAMPTZ, created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
 UNIQUE(credential_id,version), CHECK(octet_length(nonce)=12)
);
CREATE TABLE repository_credential (
 repository_id BIGINT PRIMARY KEY, credential_id BIGINT NOT NULL,
 authorization_context UUID NOT NULL UNIQUE,
 updated_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp()
);
CREATE INDEX repository_credential_ref ON repository_credential(credential_id);
ALTER TABLE collection_task ADD COLUMN authorization_context UUID;
ALTER TABLE collection_task ADD COLUMN credential_ref VARCHAR(128);
