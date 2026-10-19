
# Conveyor Manufacturing

Backend for production orders, material reservations, manufacturing operations and quality inspections. The repository contains a Java monolith and seven services extracted from it.

Java 21 / Spring Boot · Go · PostgreSQL · Kafka · Redis · Docker Compose

## Run

Requires Docker Compose v2 and Python 3.10+. The first build downloads images and dependencies.

```bash
cp .env.example .env
docker compose up --build -d --wait --wait-timeout 300
python3 scripts/demo.py
```

The demo creates an order, reserves stock, schedules operations, handles an equipment failure and completes quality checks. Seed requests are idempotent; each demo run creates a new order and consumes stock.

To run the monolith baseline, stop the other stack first:

```bash
docker compose down
docker compose -f docker-compose.yml -f compose.baseline.yml up --build -d --wait --wait-timeout 300
BASELINE=true python3 scripts/demo.py
```

Both modes use the same host ports and separate named volumes. On PowerShell, set `$env:BASELINE="true"` before running the baseline demo.

## Services

| Application | Port | Owns |
|---|---:|---|
| legacy-monolith | 8080 | Product catalog and legacy API routing |
| engineering-service | 8081 | Specifications and BOM revisions |
| production-order-service | 8082 | Order lifecycle |
| material-service | 8083 | Stock, reservations and consumption |
| production-planning-service | 8084 | Capacity lanes and scheduling |
| shop-floor-service | 8085 | Operations and equipment state |
| quality-control-service | 8086 | Measurements and inspection history |
| manufacturing-audit-service | 8087 | Event history |

Shop-floor and audit are written in Go. The other applications use Java. Each application owns a separate PostgreSQL database and role. Host ports bind to loopback.

## Architecture

The [system overview](docs/architecture/system-overview.md) includes a Mermaid deployment diagram. See [engineering decisions](docs/architecture/engineering-decisions.md) for design trade-offs and [security review](docs/security/SECURITY_REVIEW.md) for local-only credentials and deployment limitations.

## Workflow

An order keeps a snapshot of its approved BOM. Submission reserves the required materials; planning dispatch assigns seven operations to capacity lanes. Operations require start authorization, completed predecessors and available equipment.

Equipment failure pauses affected operations. A later recovery event permits explicit resume. Failed inspections retain their previous attempts. Order completion requires three facts: operations finished, materials consumed and final quality approved.

The outbox is committed with business state. Consumers commit inbox records with their effects before acknowledging Kafka. Delivery is at-least-once; repeated event IDs do not apply the same mutation twice.

## Legacy API and migration

The monolith retains `/api/legacy/...` paths and routes requests to the current owner. Ownership is stored as LOCAL, FROZEN or REMOTE. An unavailable remote service does not trigger a local write fallback.

Migration requires stopped client traffic, no active manufacturing orders and drained events. See the [migration runbook](docs/modernization/data-migration.md) for snapshot transfer, reconciliation and rollback.

## Tests

With Java 21, Maven, Go and Docker installed:

```bash
mvn -B -ntp verify
python3 scripts/go_tasks.py test
python3 scripts/go_tasks.py vet
python3 scripts/demo.py
python3 tests/api_regression.py
python3 tests/broker_recovery.py
```

The Java tests use PostgreSQL Testcontainers. API scenarios cover shortages, concurrent reservations, inspection failures and duplicate messages. The migration test compares records before and after forward/reverse transfer. Commands and prerequisites are listed in the [test matrix](docs/testing/TEST_MATRIX.md).

Historical local results and the later successful GitHub Actions verification are documented separately in the [test matrix](docs/testing/TEST_MATRIX.md).

## Development

`go.mod` and `go.sum` are committed for all three Go modules. Build and test commands use read-only dependency resolution; run `python3 scripts/go_tasks.py tidy` after intentionally changing dependencies.

```bash
make format        # google-java-format 1.24.0 and gofmt
make format-check  # also enforced in CI
python3 scripts/go_tasks.py build
```

Java formatting is checked during Maven's validation phase. Formatting does not require Docker; the full integration suite does.

## Monitoring

- Prometheus: http://localhost:9090
- Grafana: http://localhost:3000 — local credentials `admin` / `local-grafana-only`
- Java health and metrics: `/actuator/health`, `/actuator/prometheus`
- Go health and metrics: `/health`, `/metrics`

See [metric definitions](docs/architecture/observability.md) for measurement boundaries.

## Implementation notes

Writes are serialized per database. Some collection endpoints and audit queries scan their records. Scheduling uses a fixed route with configurable durations and capacity lanes; execution in the demo does not wait for planned wall-clock times.

The stack uses one PostgreSQL server and one Kafka broker. Equipment events are simulated. Authentication, shift calendars and online migration of active orders are outside the current implementation.

## Reference

- [API guide](docs/api/API.md) and [OpenAPI](docs/api/openapi.yaml)
- [Service boundaries](docs/architecture/service-boundaries.md)
- [Database schema](docs/database/SCHEMA.md)
- [Event contract](docs/events/CONTRACT.md)
- [Failure handling](docs/architecture/failure-handling.md)

Use `docker compose logs --tail=100` for diagnostics. `docker compose down` preserves volumes; adding `-v` removes the selected stack's data.
