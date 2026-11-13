-- Publish already-tracked public repositories so the owners directory and
-- public dashboards have something to show immediately after upgrade.
UPDATE repository
SET public_dashboard_enabled = true,
    public_traffic_enabled = true,
    public_referrers_enabled = true,
    public_paths_enabled = true,
    public_topics_enabled = true,
    visibility_verified_at = clock_timestamp()
WHERE tracking_enabled
  AND visibility = 'PUBLIC';

UPDATE search_query q
SET public_enabled = true
FROM repository r
WHERE q.repository_id = r.id
  AND r.tracking_enabled
  AND r.visibility = 'PUBLIC'
  AND r.public_dashboard_enabled;
