# Extraction stages

| Stage | Original responsibility | Target | Verification |
|---|---|---|---|
| 1 | Entire workflow in monolith | Runnable baseline | Java characterization suite and baseline API demo |
| 2 | Synchronous internal notifications | Durable outbox and Kafka bridge | Outbox failure/inbox rollback tests |
| 3 | Specifications/BOM | Engineering | Revision immutability and snapshot reconciliation |
| 4 | Local audit projection | Go manufacturing audit | Event ID deduplication and transferred history |
| 5 | Java operation execution | Go shop-floor | Sequence, capacity lane, equipment failure and resume |
| 6 | Local quality checks | Quality control | Failed attempt blocks completion; retry preserves history |
| 7 | Local material records | Materials | Atomic reservation, stock constraints and cancellation |
| 8 | Local planning | Production planning | Priority dispatch and lane schedules |
| 9 | Local lifecycle coordinator | Production orders | Independent completion facts and API compatibility |
| 10 | Duplicate active implementations | Read-only retained copies | Single active owner and reverse migration |

At every extraction, stop traffic, drain the integration boundary, freeze both sides, transfer stable identifiers and reconcile content before routing changes. `scripts/migrate_all.py` uses this stage order. Baseline code remains packaged for demonstration and rollback; cleanup means removing its write authority, not deleting evidence of the starting architecture.

## Architectural differences

| Baseline | Hybrid |
|---|---|
| One Java deployment | Monolith plus seven independent applications |
| One manufacturing database | Eight logical databases with separate roles |
| Synchronous in-process handlers | Kafka integration between owners |
| Shared manufacturing transaction | Local transactions plus eventual consistency |
| Java shop-floor and local history | Go execution and audit |
| Direct module calls | Reference APIs, event contracts and compatibility routing |

This comparison describes deployment and transaction boundaries. Performance benchmarks have not been run.
