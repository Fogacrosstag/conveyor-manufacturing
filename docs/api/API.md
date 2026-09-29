# Business API reference

All successful public commands return HTTP 200 with a `data` object and response timestamp. Error categories include 400 invalid JSON, 404 missing resources, 405 unsupported operation, 409 state/idempotency conflict, 422 business validation and 503 dependency unavailability. Commands require `Idempotency-Key`; reuse it for retries of the same body/path. Correlation identifiers use `X-Correlation-ID`.

| Owner | Method | Path | Behavior |
|---|---|---|---|
| products | GET | /products | List products |
| products | POST | /products | Create immutable catalog product |
| products | GET | /products/{id} | Read product |
| engineering | GET | /engineering/specifications | List specifications |
| engineering | POST | /engineering/specifications | Create revision with five checks |
| engineering | GET | /engineering/specifications/{id} | Read specification |
| engineering | POST | /engineering/specifications/{id}/approve | Approve draft |
| engineering | GET | /engineering/boms | List BOMs |
| engineering | POST | /engineering/boms | Create BOM from approved specification |
| engineering | GET | /engineering/boms/{id} | Read BOM |
| engineering | POST | /engineering/boms/{id}/approve | Approve BOM |
| material | GET | /materials | List materials |
| material | POST | /materials | Create material |
| material | GET | /materials/{id} | Read inventory document |
| material | GET | /materials/{id}/inventory | Read same inventory document |
| material | POST | /materials/receive | Receive positive quantity |
| material | GET | /materials/low-stock | Materials below threshold |
| material | GET | /materials/reservations/{id} | Read order reservation |
| production-order | GET | /production-orders | List orders |
| production-order | POST | /production-orders | Create one-product order |
| production-order | GET | /production-orders/{id} | Read order and transition history |
| production-planning | GET | /planning/work-centers | List work centers |
| production-planning | POST | /planning/work-centers | Configure route work center |
| production-planning | GET | /planning/work-centers/{id} | Read work center |
| production-planning | GET | /planning/schedules | List schedules |
| production-planning | GET | /planning/schedules/{id} | Read order schedule |
| production-planning | POST | /planning/dispatch | Dispatch pending reservations by priority |
| shop-floor | GET | /shop-floor/operations | List operations |
| shop-floor | GET | /shop-floor/operations/{id} | Read operation |
| shop-floor | POST | /shop-floor/events | Accept sequenced equipment observation |
| quality-control | GET | /quality/inspections | List inspections |
| quality-control | GET | /quality/inspections/{id} | Read latest inspection attempt |
| quality-control | GET | /quality/production-orders/{id} | List order inspections |
| manufacturing-audit | GET | /audit/events | Paginated event history |
| manufacturing-audit | GET | /audit/events/{id} | Read event |
| production-order | POST | /production-orders/{id}/submit | Lifecycle command: submit |
| production-order | POST | /production-orders/{id}/start | Lifecycle command: start |
| production-order | POST | /production-orders/{id}/hold | Lifecycle command: hold |
| production-order | POST | /production-orders/{id}/resume | Lifecycle command: resume |
| production-order | POST | /production-orders/{id}/complete | Lifecycle command: complete |
| production-order | POST | /production-orders/{id}/cancel | Lifecycle command: cancel |
| shop-floor | POST | /shop-floor/operations/{id}/start | Operation command: start |
| shop-floor | POST | /shop-floor/operations/{id}/pause | Operation command: pause |
| shop-floor | POST | /shop-floor/operations/{id}/resume | Operation command: resume |
| shop-floor | POST | /shop-floor/operations/{id}/complete | Operation command: complete |
| quality-control | POST | /quality/inspections/{id}/start | Inspection command: start |
| quality-control | POST | /quality/inspections/{id}/checks | Inspection command: checks |
| quality-control | POST | /quality/inspections/{id}/complete | Inspection command: complete |
| quality-control | POST | /quality/inspections/{id}/retry | Inspection command: retry |
| manufacturing-audit | GET | /audit/production-orders/{id} | Filter history by production-orders |
| manufacturing-audit | GET | /audit/materials/{id} | Filter history by materials |
| manufacturing-audit | GET | /audit/equipment/{id} | Filter history by equipment |
| manufacturing-audit | GET | /audit/work-centers/{id} | Filter history by work-centers |

Audit query parameters: `limit` (1–500; default 100), `offset` (default 0), `eventType`. Go operation lists optionally filter by `productionOrderId`. Other Java list APIs currently return their complete demo collection; see the scalability limitation.

## Concrete examples

Seed first using `python3 scripts/seed.py`; it prints product/BOM UUIDs. Create an order:

```bash
curl -sS http://localhost:8082/production-orders -H 'Content-Type: application/json' -H 'Idempotency-Key: example-order-001' -d '{"productId":"PRODUCT_UUID","bomRevisionId":"BOM_UUID","quantity":5,"priority":"HIGH"}'
curl -sS http://localhost:8082/production-orders/ORDER_UUID/submit -H 'Content-Type: application/json' -H 'Idempotency-Key: example-submit-001' -d '{}'
curl -sS http://localhost:8084/planning/dispatch -H 'Content-Type: application/json' -H 'Idempotency-Key: example-dispatch-001' -d '{}'
```

Wait for the order to report `SCHEDULED`, authorize start through `/production-orders/{id}/start`, and use operation IDs from `/shop-floor/operations`. `/complete` requires `processedQuantity` and `rejectedQuantity`; rejected units must be reworked before successful operation completion.

Quality measurement body: `{ "measuredValue": 10, "inspectorId": "DEMO-QA" }`. A retry requires `{ "reworkNote": "Adjusted and re-measured" }`. The executable demo provides all steps and expected assertions without manually copying UUIDs.

Migration APIs are separate, raw JSON administration responses: `/internal/migration/{capability}/{state|freeze|snapshot|import|activate|redirect}`. `redirect` is a Java legacy capability operation; Go sources remain frozen on reverse transfer. Never expose these APIs outside the local demonstration network.
