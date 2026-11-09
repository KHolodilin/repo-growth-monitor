# Assignment recovery primitive (#217 checkpoint)

AssignmentRecovery is an RGM-only transactional primitive. It is not yet an automatic node monitor and does not complete #217.

It locks the task before dispatch/attempt state, verifies expected current attempt and active request/task, enforces minimum assignment age (rgm.orchestration.recovery.minimum-age, default 2m) and maximum attempts (maximum-attempts, default 3), and chooses only another enabled configured node in the same pool. A transaction supersedes the old attempt, blocks its dispatch and clears its send lease, creates the next numbered attempt and immutable dispatch envelope, and updates current_attempt_id. jobId/requestId, business date, frozen configuration and authorization scope remain unchanged; attemptId and assignment deadline are new. It does not change GitHub quota state or move Collector data/token caches.

Dispatch acknowledgment now takes the task lock first, matching recovery ordering. A late old ACK cannot change a reassigned task. Credential resolution already requires current_attempt_id, so old assignments lose new credential access. Any failure inserting the new outbox rolls the entire reassignment back.

Validated by 13 OrchestrationIT tests, including competing recovery transactions (exactly one succeeds), stable job ID/new attempt ID, stale ACK rejection, too-recent policy and rollback after injected dispatch-insert failure. Existing orchestration tests remain green. No deployment or production routing switch was performed.

Outstanding before #217 is complete: assigned execution and result application (#215/#216), monitor/status transport and policy-controlled automatic decisions, stale callback/application races, partition/recovery failure injection, backend restart reconciliation and the measured 100-repository workload. Node unreachability is not proof execution stopped. No automatic recovery loop is enabled by this checkpoint.
