# Failure handling

| Failure | Durable response | Recovery |
|---|---|---|
| Insufficient material | `MaterialReservationFailed`; order stays pending | Receive stock, resubmit pending order |
| Concurrent reservation | PostgreSQL serialization plus stock constraints | Losing order receives shortage result |
| Equipment unavailable | Paused operation, unavailable equipment, delayed schedule | Higher-sequence recovery, then explicit resume |
| Failed inspection | Failed immutable attempt; no final approval | Append retry with rework note, re-measure |
| Kafka publication failure | Outbox remains unpublished | Worker retries; inspect backlog metric |
| Consumer handler failure | Transaction/inbox roll back | Four bounded attempts, then confirmed DLQ publication |
| DLQ unavailable | Input offset is not acknowledged | Restore broker; Go consumer requires restart and reports unhealthy |
| HTTP dependency unavailable | 503, no fallback write | Restore owner; retry same request key |
| Migration mismatch | Both owners remain frozen | Inspect retained snapshots and reconciliation failure |

Consumers can process later messages after DLQ recovery, so operators must repair/replay failed prerequisite events before declaring business state healthy. This is not an automatically compensating general-purpose workflow engine.

Outbox workers hold a service-local transaction lock while waiting for acknowledgements. It makes ordering easy to reason about but can increase command latency during broker failure. This tradeoff is documented, not a throughput optimization.
