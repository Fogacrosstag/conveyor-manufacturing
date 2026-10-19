
# Engineering Decisions and Trade-offs

## Overview

Conveyor Manufacturing demonstrates incremental modernization of a legacy manufacturing backend using the Strangler Fig pattern, independently deployed capability services, asynchronous messaging and explicit data ownership.

The architecture prioritizes migration safety, behavioural compatibility and reliable event processing over maximum throughput.

This document describes the principal engineering decisions, their motivations and their limitations.

## Architecture Decision Summary

| Decision | Motivation | Trade-off |
|---|---|---|
| Incremental legacy modernization | Preserve existing functionality while extracting capabilities | Temporary coexistence of monolith and services |
| Capability-based service boundaries | Establish clear ownership of manufacturing operations | Cross-service coordination becomes more complex |
| Kafka event-driven communication | Decouple business processes across services | Eventual consistency and broker dependencies |
| Transactional outbox | Persist business changes and event publication intent together | Additional database writes and publishing infrastructure |
| Consumer inbox and idempotency | Prevent duplicate application of retried events | Additional storage and deduplication logic |
| Separate logical PostgreSQL databases | Enforce service-level data ownership | Cross-service queries require integration |
| Java and Go services | Demonstrate language-independent service contracts | Multiple build tools and operational requirements |
| Advisory locks for mutations | Simplify concurrency correctness | Reduced write concurrency |
| Quiescent migration | Support deterministic data transfer and reconciliation | Migration requires operational downtime |

## 1. Incremental Modernization

The project follows a Strangler Fig modernization strategy.

Instead of replacing the entire legacy monolith, individual manufacturing capabilities are extracted into independently deployed services.

The monolith retains compatibility with existing API endpoints while forwarding requests to the appropriate capability owner.

### Benefits

- Existing API contracts can remain available during migration.
- Capabilities can be extracted incrementally.
- Individual services can evolve without replacing the entire application.
- Migration and rollback can be verified through dedicated scenarios.

### Limitations

- The legacy monolith and extracted services must coexist temporarily.
- Ownership routing introduces additional operational complexity.
- Migration requires explicit coordination between application state and event processing.

## 2. Capability-Based Service Boundaries

Each extracted service owns a distinct manufacturing capability.

The services include engineering, production orders, materials, production planning, shop-floor execution, quality control and manufacturing audit.

Each capability has responsibility for its business state and associated operations.

This reduces direct coupling between domains but requires explicit communication contracts.

## 3. Event-Driven Communication

Kafka is used to exchange domain events between capabilities.

This allows services to react to manufacturing state changes without requiring every operation to be implemented as a synchronous chain of HTTP requests.

### Benefits

- Reduced direct coupling between services.
- Support for asynchronous manufacturing workflows.
- Explicit event contracts.
- Independent event consumers.

### Limitations

- Events may be delivered more than once.
- Consumers must handle retries and duplicates.
- Cross-service state can be temporarily inconsistent.
- Kafka availability becomes an operational dependency.

The system uses at-least-once delivery semantics and does not claim exactly-once event delivery.

## 4. Transactional Outbox and Consumer Inbox

The transactional outbox records publication intent in the same database transaction as the associated business mutation.

A separate publishing process forwards pending records to Kafka.

Consumers use inbox records to track processed events and avoid applying the same event multiple times.

This design reduces the risk of losing publication intent when a database transaction succeeds but the broker is temporarily unavailable.

It does not eliminate the need for retries, monitoring and operational recovery.

## 5. Database Ownership

Each application uses its own logical PostgreSQL database and database role.

The local Docker Compose environment uses a shared PostgreSQL server.

### Benefits

- Explicit ownership of business data.
- Reduced accidental cross-service modification.
- Independent logical database boundaries.

### Limitations

- The local environment still depends on one physical PostgreSQL server.
- Cross-service joins are not used as an integration mechanism.
- Business processes spanning multiple services require coordination through APIs and events.

## 6. Concurrency and Consistency

Business mutations are serialized per database using an advisory lock.

This simplifies concurrency correctness for the demonstrated workflows.

The trade-off is reduced write parallelism, making the approach unsuitable for assuming production-scale throughput without additional testing and redesign.

## 7. Technology Selection

### Java 21 and Spring Boot

Java and Spring Boot are used for the legacy monolith and most extracted capabilities.

They provide a consistent application model for transactional business operations, HTTP APIs and integration with PostgreSQL and Kafka.

### Go

Go is used for the shop-floor and manufacturing-audit services.

This demonstrates service communication across different programming languages while retaining shared API and event contracts.

### PostgreSQL

PostgreSQL provides transactional persistence for business state, outbox records, inbox records and migration data.

### Kafka

Kafka provides asynchronous domain-event transport between services.

### Docker Compose

Docker Compose provides reproducible local deployment for the baseline and hybrid configurations.

It is not presented as a production orchestration platform.

## 8. Migration and Rollback

Forward and reverse migration are implemented with explicit reconciliation.

The migration process requires:

- Quiescent client traffic.
- Drained event processing.
- No active manufacturing orders.
- Verification of transferred records.

Online migration of active manufacturing orders is outside the demonstrated scope.

## 9. Known Limitations

The repository is an engineering demonstration rather than a production-ready manufacturing platform.

The following capabilities are not fully demonstrated:

- Production authentication and authorization.
- High-availability Kafka and PostgreSQL.
- Production secret management.
- Real industrial equipment integrations.
- Shift-calendar scheduling.
- Production-scale load testing.
- Online migration of active manufacturing orders.

## 10. Further Reading

- [System overview](system-overview.md)
- [Service boundaries](service-boundaries.md)
- [Event-driven architecture](event-driven-architecture.md)
- [Failure handling](failure-handling.md)
- [Migration runbook](../modernization/data-migration.md)
- [Testing matrix](../testing/TEST_MATRIX.md)
