# Shared GitHub budget

The completed implementation and current YAML defaults are documented in [collector-github-budget.md](collector-github-budget.md).

All assigned Collector source requests use the shared RGM authority plus Bucket4j local pacing. Principal aliases, durable cooldowns, Admin diagnostics and assigned execution replace the earlier per-reference checkpoint. Recovery and workload measurements are in [collector-recovery.md](collector-recovery.md). Production activation and full release validation remain part of the final release gate.
