-- Additive product schema. All relationships here are logical; no physical FK.
CREATE TABLE collector_node (
 id BIGSERIAL PRIMARY KEY, node_key VARCHAR(64) NOT NULL UNIQUE,
 pool VARCHAR(64) NOT NULL, endpoint TEXT NOT NULL, enabled BOOLEAN NOT NULL,
 updated_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp()
);
CREATE INDEX collector_node_pool ON collector_node(pool,id) WHERE enabled;
CREATE TABLE collection_request (
 id BIGSERIAL PRIMARY KEY, public_id UUID NOT NULL UNIQUE, repository_id BIGINT NOT NULL,
 trigger_type VARCHAR(16) NOT NULL CHECK(trigger_type IN ('MANUAL','SCHEDULED')),
 trigger_key TEXT UNIQUE, business_date DATE NOT NULL,
 state VARCHAR(16) NOT NULL DEFAULT 'ACTIVE' CHECK(state IN ('ACTIVE','SUCCEEDED','FAILED','CANCELLED')),
 created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(), completed_at TIMESTAMPTZ
);
CREATE UNIQUE INDEX collection_request_active ON collection_request(repository_id) WHERE state='ACTIVE';
CREATE INDEX collection_request_history ON collection_request(repository_id,created_at DESC,id DESC);
CREATE TABLE collection_task (
 id BIGSERIAL PRIMARY KEY, public_id UUID NOT NULL UNIQUE, request_id BIGINT NOT NULL,
 repository_id BIGINT NOT NULL, task_type VARCHAR(32) NOT NULL, target_key TEXT NOT NULL,
 parameters JSONB NOT NULL, configuration_snapshot JSONB NOT NULL, configuration_revision BIGINT NOT NULL,
 current_attempt_id UUID NOT NULL, state VARCHAR(16) NOT NULL DEFAULT 'READY',
 created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(), completed_at TIMESTAMPTZ,
 UNIQUE(request_id,task_type,target_key)
);
CREATE INDEX collection_task_request ON collection_task(request_id,id);
CREATE TABLE collection_attempt (
 id BIGSERIAL PRIMARY KEY, public_id UUID NOT NULL UNIQUE, task_id BIGINT NOT NULL,
 attempt_no INTEGER NOT NULL, node_id BIGINT NOT NULL,
 state VARCHAR(16) NOT NULL DEFAULT 'ASSIGNED', collector_job_id UUID,
 assigned_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(), accepted_at TIMESTAMPTZ, completed_at TIMESTAMPTZ,
 UNIQUE(task_id,attempt_no)
);
CREATE UNIQUE INDEX collection_attempt_active ON collection_attempt(task_id) WHERE state IN ('ASSIGNED','ACCEPTED','RUNNING');
CREATE INDEX collection_attempt_node ON collection_attempt(node_id,state,assigned_at);
CREATE TABLE collector_dispatch (
 id BIGSERIAL PRIMARY KEY, attempt_id UUID NOT NULL UNIQUE, endpoint TEXT NOT NULL,
 payload JSONB NOT NULL, deadline_at TIMESTAMPTZ NOT NULL,
 state VARCHAR(16) NOT NULL DEFAULT 'READY' CHECK(state IN ('READY','RETRY','SENDING','ACKED','BLOCKED','EXPIRED')),
 send_count INTEGER NOT NULL DEFAULT 0, generation BIGINT NOT NULL DEFAULT 0,
 locked_by VARCHAR(128), locked_until TIMESTAMPTZ,
 next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(), last_error VARCHAR(64),
 created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(), acknowledged_at TIMESTAMPTZ,
 CHECK ((state='SENDING') = (locked_by IS NOT NULL AND locked_until IS NOT NULL))
);
CREATE INDEX collector_dispatch_due ON collector_dispatch(next_attempt_at,id) WHERE state IN ('READY','RETRY');
CREATE INDEX collector_dispatch_expired ON collector_dispatch(locked_until,id) WHERE state='SENDING';
CREATE TABLE collector_result_receipt (
 id BIGSERIAL PRIMARY KEY, event_id UUID NOT NULL UNIQUE, result_id UUID NOT NULL UNIQUE,
 attempt_id UUID NOT NULL UNIQUE, task_id BIGINT NOT NULL, node_id BIGINT NOT NULL,
 payload_hash VARCHAR(64) NOT NULL, outcome VARCHAR(16) NOT NULL,
 received_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp()
);
CREATE INDEX collector_receipt_task ON collector_result_receipt(task_id,received_at DESC);
