# Metrics, logs and tracing

| Metric | Meaning |
|---|---|
| `production_orders{status}` | Persisted order count in the scraped owner's database |
| `manufacturing_operations{status}` | Persisted Go operation count in each state |
| `quality_inspection_attempts` | Immutable completed inspection attempts |
| `quality_inspection_failed_attempts` | Attempts whose measured result failed |
| `production_delayed_schedules` | Schedules with an observed delay flag |
| `equipment_downtime_seconds` | Accumulated downtime plus currently open outage duration |
| `manufacturing_operation_average_duration_seconds` | Mean elapsed actual start-to-complete interval of completed operations, including pauses |
| `outbox_pending_events` | Unpublished durable events |
| `kafka_messages_processed_total` | Successful incoming deliveries in the current process, including deduplicated deliveries |
| `kafka_messages_failed_total` | Incoming records confirmed in DLQ in the current process |

Quality attempt pass fraction = 1 - failed attempts / total attempts; the dashboard protects division by zero. It is an attempt-level fraction, not an order-level acceptance rate. State gauges survive restarts because their source is PostgreSQL; process counters reset. Scrape the authoritative owner rather than adding historical legacy copies after migration.

Downtime uses server receipt time. Simulator timestamps describe observations; elapsed downtime is measured by the server. Operation durations include pause time; they are not machine utilization or OEE.

Java ECS logs and Go slog logs are structured. Java request/consumer context includes a domain correlation ID. HTTP spans export through OTLP to the collector's debug exporter. Event envelopes carry correlation IDs; full W3C producer/consumer spans and Kafka processing-latency histograms remain a future extension.
