ALTER TABLE collector_job ADD COLUMN failure_count INTEGER NOT NULL DEFAULT 0;
CREATE TABLE collector_observation (
 job_id BIGINT NOT NULL, observation_key TEXT NOT NULL, payload JSONB NOT NULL,
 payload_bytes INTEGER NOT NULL CHECK(payload_bytes>=0), observed_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
 PRIMARY KEY(job_id,observation_key)
);
