# 2026-10-11 live acceptance scripts

These are the recorded scripts for the M00–M08 acceptance session, not a production data loader or a Maven test suite. They create clearly prefixed synthetic tenants/catalog/ledger records in the existing local database. They never clear business tables. The full acceptance result is in `docs/M00-M08-联调验收报告-2026-10-11.md`.

Execution order used:

1. `pwsh -File tests/acceptance/run-live-acceptance.ps1` (records the generated run name).
2. Start a temporary second application on port 8086 using the IDEA process classpath and Java 21; record its PID in `tests/results/second-instance.pid`. The original process stays on 8085.
3. `pwsh -File tests/acceptance/run-live-extended.ps1 -Run <run>`.
4. `pwsh -File tests/acceptance/run-live-final.ps1 -Run <run>`.
5. `pwsh -File tests/acceptance/run-live-close.ps1 -Run <run>`.

`resume-live-extended.ps1` records the continuation after correcting the original dead-letter fixture; do not run it after a successful full extended phase. Phase ordering and natural-month fixtures are specific to this acceptance session (October 2026). Some scenario IDs and cleanup identifiers intentionally preserve this execution's evidence. Review/adapt them before a future run; these scripts are not advertised as a general CI acceptance runner.

Docker access is required. The extended/final phases briefly stop and restore Redis/RabbitMQ with `finally` recovery. The close phase stops only the PID whose command line matches the temporary 8086 application, disables this run's synthetic accounts and revokes its keys. Synthetic accounting records are kept for inspection. The close phase also performs its recorded renewal and role checks, so it is not a standalone general cleanup command.

Credentials are read from the ignored local `.env`; temporary cross-phase auth data is stored under the current user's TEMP and removed at close. HTTP evidence omits login/key issuance secrets. `tests/results/` is Git-ignored and must be preserved locally with the report if the evidence is needed later. Raw checks include harness retries; consult `adjudication.json` rather than counting raw lines.

## 2026-10-12 G01–G04 follow-up

This session uses a single application on 8086, with 8085 stopped. It does not use the old two-instance startup/close sequence above.

1. `run-gap-setup.ps1` creates the prefixed synthetic dataset and records its run ID.
2. `run-gap-performance.ps1 -Run <run> -Label <unique-label>` executes bounded and unlimited load, resource sampling, drain, exact billing and reconciliation checks. Use a new dataset and label for each complete comparison; do not overwrite old evidence.
3. `analyze-gap-performance.ps1 -Run <run> -Label <label>` derives processing throughput, exact HTTP-end backlog and ingest-to-processed latency from JTL and database timestamps.
4. `run-gap-features.ps1 -Run <run>` verifies platform permissions, independent channel recovery and reversal on the generated unpaid performance invoice.
5. `close-gap-experiments.ps1` revokes keys and disables only this session's three explicitly listed synthetic account pairs, removes their temporary credentials, and leaves 8086 running. It intentionally preserves financial and usage evidence.

`complete-gap-performance.ps1` recovers verification after an interrupted run; it does not make interrupted timing comparable. The first baseline's interruption and source filter issue remain documented in its adjudication file. See `docs/G01-G04-修复与性能优化报告-2026-10-12.md` for the final comparison and deferred work. Apifox is user-owned.
