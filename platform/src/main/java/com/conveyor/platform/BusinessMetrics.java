package com.conveyor.platform;

import io.micrometer.core.instrument.*;
import org.springframework.stereotype.Component;

@Component
public class BusinessMetrics {
  public BusinessMetrics(Store s, MeterRegistry registry) {
    for (String status :
        new String[] {
          "CREATED",
          "MATERIALS_PENDING",
          "MATERIALS_RESERVED",
          "SCHEDULED",
          "IN_PROGRESS",
          "QUALITY_PENDING",
          "COMPLETED",
          "CANCELLED",
          "ON_HOLD"
        }) {
      Gauge.builder(
              "production_orders",
              s,
              db ->
                  db.db
                      .queryForObject(
                          "select count(*) from records where kind='order' and body->>'status'=?",
                          Long.class,
                          status)
                      .doubleValue())
          .tag("status", status)
          .description("Current persisted production orders by state")
          .register(registry);
    }
    Gauge.builder(
            "quality_inspection_attempts",
            s,
            db ->
                db.db
                    .queryForObject(
                        "select count(*) from records where kind='quality-attempt'", Long.class)
                    .doubleValue())
        .register(registry);
    Gauge.builder(
            "quality_inspection_failed_attempts",
            s,
            db ->
                db.db
                    .queryForObject(
                        "select count(*) from records where kind='quality-attempt' and"
                            + " body->>'result'='FAILED'",
                        Long.class)
                    .doubleValue())
        .register(registry);
    Gauge.builder(
            "production_delayed_schedules",
            s,
            db ->
                db.db
                    .queryForObject(
                        "select count(*) from records where kind='schedule' and"
                            + " body->>'delayed'='true'",
                        Long.class)
                    .doubleValue())
        .register(registry);
    Gauge.builder(
            "equipment_downtime_seconds",
            s,
            db ->
                db.db.queryForObject(
                    "select coalesce(sum(coalesce((body->>'downtimeSeconds')::double"
                        + " precision,0)+case when body->>'status'='UNAVAILABLE' and body ?"
                        + " 'unavailableSince' then extract(epoch from"
                        + " (now()-(body->>'unavailableSince')::timestamptz)) else 0 end),0) from"
                        + " records where kind='equipment'",
                    Double.class))
        .register(registry);
    Gauge.builder(
            "manufacturing_operation_average_duration_seconds",
            s,
            db ->
                db.db.queryForObject(
                    "select coalesce(avg(extract(epoch from"
                        + " ((body->>'actualCompletionTime')::timestamptz-(body->>'actualStartTime')::timestamptz))),0)"
                        + " from records where kind='operation' and body->>'status'='COMPLETED'",
                    Double.class))
        .register(registry);
  }
}
