# Cutover runbook

Migration is a maintenance operation. Stop client traffic, complete or cancel every submitted order, drain all outboxes, and verify zero consumer lag. The system does not promise online transfer of active sagas. Keep the same Compose project/volumes throughout migration.

## Forward transfer from an existing baseline

First run the baseline demonstration, then stop new client traffic. Start the extracted applications with migration initialization:

```bash
docker compose -f docker-compose.yml -f compose.baseline.yml -f compose.migration.yml --profile modern up --build -d --wait --wait-timeout 300
COMPOSE_PROJECT_NAME=conveyor-baseline python3 scripts/wait_quiescent.py
python3 scripts/migrate_all.py --quiescent-confirmed
```

`compose.migration.yml` keeps the monolith in baseline integration mode with its Kafka consumer enabled. New extracted databases initialize as FROZEN; newly initialized consumer groups begin at the end of existing topics. This intentionally avoids replaying the complete pre-cutover history into an empty operational database. Audit history is transferred as records. Existing groups retain their offsets, so the migration overlay is not a way to discard an existing consumer backlog.

The wait script checks registered groups, observed lag and the legacy outbox. Operators must also confirm that no client can submit work; a one-time lag check is not a network write fence. `migrate_all.py` rejects active orders and freezes every selected legacy and extracted capability before transfer.

For each capability, `migrate.py`:

1. Confirms both sides are frozen and their outboxes are empty.
2. Exports source and pre-import target records to `artifacts/migration/`.
3. Imports the source snapshot in one target transaction.
4. Re-exports the target and compares canonical content SHA-256 plus per-kind counts.
5. Activates the target, then redirects the legacy capability.

Identifiers, engineering revisions, quantities, history and successful request records are preserved. Snapshots do not copy Flyway/golang-migrate histories or another service's outbox identity. The old legacy business records remain frozen copies. SQL constraints validate imports as well as normal writes.

The tools require a maintenance token (`ADMIN_TOKEN`), defaulting to the documented local demonstration value. They are not a secure production migration control plane. Interrupted/mismatched imports leave owners frozen; inspect the saved target-before snapshot before deciding how to recover.

## Partial extraction

The same single-capability transfer can be run with `scripts/migrate.py CAPABILITY --source URL --target URL --quiescent-confirmed`. Follow the stage order and keep clients stopped for each stage. The monolith must retain BASELINE=true and CONSUMER_ENABLED=true for the hybrid in-process/asynchronous bridge. Never launch an unmigrated target as an active second owner.

## Reverse transfer

Stop traffic and drain events again. Reverse migration copies current extracted records back; it is not a flag toggle:

```bash
python3 scripts/migrate_all.py --reverse --quiescent-confirmed
```

Each extracted source remains frozen, while the imported legacy owner becomes LOCAL. Retain reverse reconciliation artifacts. Do not restart obsolete active targets or reset owner records. The integration test `tests/migration_regression.py` checks forward and reverse data equality plus legacy API parity on a quiescent demonstration stack.

## Modernization strategy

Characterize the baseline before changing deployment ownership. Keep the extracted Java capability modules reusable in the monolith; replace the baseline Java shop-floor with its Go implementation behind the same API and event contracts. Move audit as an independent projection.

The runtime states are baseline, partially extracted and fully hybrid. Durable owner records make the transition inspectable. Environment flags establish an empty database's starting state; they cannot revert a migrated capability on restart.

Both deployment modes use the same business contracts and migration tooling.

## Strangler routing

Clients can keep `/api/legacy/production-orders/{id}` while its authoritative handler moves. The monolith strips the compatibility prefix, identifies the capability and reads the durable `_owner` record. LOCAL invokes the module; REMOTE calls the extracted API; FROZEN rejects mutations.

A remote timeout returns 503. It does not make a stale monolith copy writable. Owners are transferred by the administration workflow after reconciliation, never by a general-purpose environment toggle.

## Legacy adapter contract

Both modes preserve the same public document contract and UUID identifiers. The adapter normalizes the legacy path, resolves ownership, forwards correlation/idempotency headers and maps dependency errors.

Products remain local. Engineering reads product/material reference APIs; orders snapshot an approved BOM. New services do not import legacy persistence classes or share the legacy connection. The reusable Java modules encapsulate business behavior; transport and database ownership change during extraction.

## Compatibility acceptance

For a fixed order UUID, compare `data` from the authoritative `/production-orders/{id}` endpoint with `data` from `/api/legacy/production-orders/{id}`. Timestamps outside the data envelope are response timestamps and are not compared.

`scripts/demo.py` performs this comparison after completion. `tests/migration_regression.py` compares the complete order collection before migration, after forward transfer and after reverse transfer. Successful request records migrate with their capability so a retried accepted command does not create another order.

Compatibility covers the paths listed in the API reference. The API reference lists supported operations.

## Rollback

A snapshot is not permission to reactivate a stale owner. If an extracted service has accepted writes, freeze it, drain events, export its current authoritative state, import and reconcile into the frozen legacy capability, and only then activate the legacy owner.

Use `scripts/migrate_all.py --reverse --quiescent-confirmed` after maintenance preconditions. The remote owner remains frozen. Save source, target-before and reconciliation files outside the disposable environment before deleting volumes.

If import or comparison fails, both sides stay frozen. Repair under the same maintenance window or restore a reviewed target-before snapshot; do not bypass a failed checksum or edit an ownership record to make the error disappear.
