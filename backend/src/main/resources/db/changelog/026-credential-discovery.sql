-- Credential-scoped discovery without a seed repository. No physical FKs (same style as 016).
ALTER TABLE collection_request ALTER COLUMN repository_id DROP NOT NULL;
ALTER TABLE collection_task ALTER COLUMN repository_id DROP NOT NULL;
ALTER TABLE collection_request ADD COLUMN IF NOT EXISTS credential_ref VARCHAR(128);

DROP INDEX IF EXISTS collection_request_active;
CREATE UNIQUE INDEX collection_request_active
  ON collection_request(repository_id)
  WHERE state = 'ACTIVE' AND repository_id IS NOT NULL;
CREATE UNIQUE INDEX collection_request_active_discovery
  ON collection_request(credential_ref)
  WHERE state = 'ACTIVE' AND repository_id IS NULL AND credential_ref IS NOT NULL;

CREATE TABLE credential_discovery_scope (
  credential_id BIGINT PRIMARY KEY,
  authorization_context UUID NOT NULL UNIQUE,
  updated_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp()
);
CREATE INDEX credential_discovery_scope_context ON credential_discovery_scope(authorization_context);
