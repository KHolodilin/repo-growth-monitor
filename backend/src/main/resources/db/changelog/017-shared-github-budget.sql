-- Control-plane state is shared by all Collector nodes and survives their replacement.
-- Identity keys describe GitHub quota principals, never node IDs or token versions.
CREATE TABLE github_budget (
 identity_key VARCHAR(128) NOT NULL,
 resource VARCHAR(32) NOT NULL CHECK(resource IN ('core','search')),
 remaining BIGINT NOT NULL CHECK(remaining >= 0),
 reset_at TIMESTAMPTZ NOT NULL,
 next_request_at TIMESTAMPTZ NOT NULL,
 cooldown_until TIMESTAMPTZ,
 updated_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
 PRIMARY KEY(identity_key,resource)
);
CREATE TABLE github_request_permit (
 permit_id UUID PRIMARY KEY,
 identity_key VARCHAR(128) NOT NULL UNIQUE,
 node_key VARCHAR(64) NOT NULL,
 attempt_id UUID NOT NULL,
 resource VARCHAR(32) NOT NULL,
 generation BIGINT NOT NULL,
 leased_until TIMESTAMPTZ NOT NULL,
 created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp()
);
CREATE INDEX github_request_permit_expired ON github_request_permit(leased_until);
CREATE TABLE github_budget_receipt (
 permit_id UUID PRIMARY KEY,
 reported_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp()
);
