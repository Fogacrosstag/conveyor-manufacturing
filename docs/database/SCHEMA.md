# Persistence schema

All owners use independent instances of these infrastructure tables:

| Table | Key | Purpose |
|---|---|---|
| `records` | `(kind, id)` | JSONB aggregate documents and local ownership/idempotency metadata |
| `outbox_events` | `sequence`; unique `event_id` | Ordered eventual publication, retries and acknowledgement time |
| `processed_events` | `(event_id, consumer_name)` | Transactional inbox deduplication |

The database migration schema is versioned separately from domain document revisions. Flyway applies V1 infrastructure and per-application V2 constraints. golang-migrate applies the equivalent embedded 000001/000002 SQL. Java and Go migration-history tables are local implementation metadata and are not copied during cutover.

V2 unique indexes protect product/material codes, BOM/specification revisions and work-center identities. Check constraints enforce positive integral order quantities, non-negative stock, reserved <= on-hand and exact available balances. Application rules validate reference existence, immutable approved BOMs and transitions.

`Store.atomic` / `App.Atomic` take advisory transaction lock 8301 in their own database. Every HTTP mutation, handler, import and publisher follows that convention. Direct ad-hoc SQL bypasses business rules and is unsupported. The JSONB model is an explicit demonstration tradeoff; a production implementation should consider normalized aggregates and finer locking.

Snapshots contain only capability record kinds plus successful HTTP request records. IDs and business references are preserved. Audit event records are a projection. Event inbox/outbox metadata is not blindly copied into another service identity; migration drains consumers and initializes new groups before transfer. Completed reservations also have business-level replay guards.
