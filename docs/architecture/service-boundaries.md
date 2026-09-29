# Service boundaries

| Capability | Hybrid owner | Record kinds |
|---|---|---|
| products | legacy-monolith | product |
| engineering | engineering-service | specification, bom |
| production-order | production-order-service | order |
| material | material-service | material, reservation, material-cancelled |
| production-planning | production-planning-service | work-center, planning-queue, schedule, planning-cancelled |
| shop-floor | shop-floor-service | operation, shop-order, equipment, equipment-observation |
| quality-control | quality-control-service | inspection, quality-attempt, quality-order |
| manufacturing-audit | manufacturing-audit-service | audit-event |

Capability boundaries follow decisions rather than individual tables: material reservations belong with stock; inspections belong with quality approval; the order service alone arbitrates start versus cancellation. Audit is a projection and cannot issue production commands.

Every owner also owns its `_request:CAPABILITY` HTTP idempotency records. `_owner`, `processed_events` and `outbox_events` are local infrastructure. Old legacy copies are retained read-only after cutover so migration can be inspected; routing never silently restores them as authoritative data.

## Storage model

Java `Store` and Go `Unit` persist aggregates as JSONB documents in `records(kind, id, body)`. The libraries share a storage convention, while each deployed application has separate records and credentials. Migrations add uniqueness and stock constraints; business handlers validate document fields and state transitions.

This keeps the example's baseline and extracted implementations comparable. It also means field changes need explicit migration and validation, and most queries load records by kind rather than using a normalized relational model. Advisory lock 8301 serializes mutations within each database; it is a correctness mechanism with a throughput cost.

## Database isolation

`infrastructure/postgres/init.sh` creates separate roles/databases and revokes public database connection rights. Application credentials cannot connect to other owners' databases. The bootstrap database account is used only by initialization, not by application containers.

Only the administration migration API can import another owner's exported snapshot under a write fence. This is an explicit ownership transfer, not routine cross-service database access. Application APIs never offer arbitrary SQL or aggregate import.

## Legacy baseline

The baseline is a real Spring Boot application. `Products`, `LegacyShopFloor` and `LegacyAudit` live in the monolith. Engineering, orders, materials, planning and quality are linked as Java capability modules. These extracted modules remain reusable so their behavior can be characterized in the original single-process deployment.

`Store.atomic` opens one PostgreSQL transaction and takes transaction advisory lock 8301. `Bus.emit` inserts a durable outbox row, then calls local capabilities synchronously. For example, order submission can reserve materials, update order state and queue planning inside the same transaction. A failure rolls back all of those changes. This coupling is the modernization motivation.

The database contains JSONB aggregate records grouped by kind, an outbox and an inbox.

Products stay in the monolith after extraction. Durable owner records route supported legacy paths to new owners. No new service queries the legacy database.

## Target deployment

Five Java applications each depend on `platform` and their own domain module. They can be built with `mvn -pl services/NAME-service -am package`. The monolith links the same modules for its baseline state. The Go shop-floor service replaces the baseline Java execution implementation; audit replaces local history.

Each application runs on port 8080 inside the network and has its own database role. External loopback ports are mapped in the root README. Kafka carries cross-capability business transitions; reference reads for products and approved BOMs use HTTP. This keeps facts authoritative while snapshotting engineering definitions for existing orders.

The modern order saga is deliberately small: submit, reserve, dispatch, authorize start, finish operations, consume materials, approve quality, complete. Material consumption and quality may arrive in either order. Completion requires both plus operation completion.

Shared infrastructure does not own another service's records. A single physical database server and broker are local deployment choices; the logical ownership boundaries survive a move to separate servers.

## Integration patterns

`Api` identifies the capability from the URL. Durable ownership chooses local execution or `Gateway.call`. Prefix removal implements `/api/legacy/...` compatibility; response envelopes and business document shapes are preserved. Correlation and idempotency headers survive forwarding, and non-success dependency responses map to the same HTTP error category.

The anti-corruption boundary deliberately uses the same documented public document fields in both modes. It prevents legacy database access and deployment details from reaching business callers.

HTTP reference reads have connect/request deadlines. There is no unsafe write fallback. A failed dependency leaves the initiating transaction uncommitted; clients may retry with the same idempotency key. Business data and the successful response cache commit atomically.
