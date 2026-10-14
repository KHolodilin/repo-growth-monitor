# Security policy

## Supported versions

Security fixes go to the latest release and to `main`. Older releases are not patched separately.

## Reporting a vulnerability

Open a [private security advisory](https://github.com/KHolodilin/repo-growth-monitor/security/advisories/new). Do not file a public issue, and do not include a GitHub token, database password, or the contents of `.env`.

Describe the impact, the steps to reproduce, and the version or commit you used. The maintainer will confirm receipt and follow up on the advisory.

The app stores a fine-grained GitHub token only in the environment. A report that shows the token can be read back from the API, the database, or a log is in scope.
