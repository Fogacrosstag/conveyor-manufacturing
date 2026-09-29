# Domain event contract

`event.schema.json` defines envelope v1. Runtime validation checks supported event types/version, UUID envelope identifiers, timestamp, non-empty source and object payload. Each handler validates the payload fields relevant to its business operation. Unknown versions/types go through the same retry/DLQ path as other invalid messages. A schema registry is not configured.

| Topic | Publisher |
|---|---|
| `engineering.events` | Engineering |
| `production-order.events` | Orders |
| `material.events` | Materials |
| `production-planning.events` | Planning |
| `shop-floor.events` | Shop-floor, including equipment state changes |
| `quality-control.events` | Quality |
| `equipment.events` | Optional envelope-based equipment ingress |
| `legacy.events` | Baseline/partially extracted monolith |

Every topic has `.dlq`, three partitions and replication factor one in local Compose. Keys are the event aggregate ID. Manufacturing-order events use the order ID; equipment-state events use equipment ID and additionally include the order reference in their payload. No ordering is assumed between those keys or between topics.

`ProductionOrderSubmitted` includes product, BOM revision/snapshot, checks, quantity, priority and calculated requirements. `MaterialReserved` preserves that order context. `ProductionScheduleApproved` adds seven tasks with UUID, work center, equipment, sequence, lane and UTC start/end. The shop-floor stores that immutable execution context and includes it in `AllManufacturingOperationsCompleted`. Quality and material services independently publish completion facts.

## Reliability

Business mutation + outbox insert share one transaction. A single serialized publisher per database reads pending sequence order, waits for Kafka acknowledgement and marks publication. Crash-after-send can duplicate a record. Producers enable Kafka session idempotence; it does not eliminate that crash window.

Business mutation + inbox insert also share one transaction. Commit precedes broker offset acknowledgement. A replayed event ID does not apply effects twice. Distinct repeated commands are additionally guarded by domain state: consumed reservations never reserve again; completed operations return their result; cancelled orders keep tombstones.

## Dead letters

A DLQ record contains `originalTopic`, `partition`, `offset`, `failure` and the original serialized `record`. Export one selected record, diagnose and repair its cause, then run:

```bash
python3 scripts/dlq_replay.py artifacts/selected-dlq-record.json --confirm-replay
```

The original event ID is retained. Inspect resulting state and consumer lag before closing the incident. Replay does not delete the DLQ record, and this local tool is not an unrestricted production administration service.
