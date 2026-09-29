package com.conveyor.platform;

import io.micrometer.core.instrument.MeterRegistry;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(
    name = "app.messaging-enabled",
    havingValue = "true",
    matchIfMissing = true)
public class Messaging {
  private final Store store;
  private final KafkaTemplate<String, String> kafka;
  private final List<Capability> caps;
  private final String service;
  private final MeterRegistry metrics;

  public Messaging(
      Store s,
      KafkaTemplate<String, String> k,
      List<Capability> c,
      @Value("${spring.application.name}") String name,
      MeterRegistry m) {
    store = s;
    kafka = k;
    caps = c;
    service = name;
    metrics = m;
    m.gauge(
        "outbox_pending_events",
        this,
        x ->
            s.db
                .queryForObject(
                    "select count(*) from outbox_events where published_at is null", Long.class)
                .doubleValue());
  }

  // Keep publication ordered: a failed acknowledgement stops this batch.
  @Scheduled(fixedDelayString = "${app.outbox-delay:250}")
  public void publish() {
    try {
      store.atomic(
          () -> {
            var rows =
                store.db.queryForList(
                    "select sequence,event_id,aggregate_id,topic,payload::text as body from"
                        + " outbox_events where published_at is null order by sequence limit 20");
            for (var row : rows) {
              try {
                kafka
                    .send(
                        row.get("topic").toString(),
                        row.get("aggregate_id").toString(),
                        row.get("body").toString())
                    .get(10, TimeUnit.SECONDS);
                store.db.update(
                    "update outbox_events set published_at=now() where sequence=?",
                    row.get("sequence"));
              } catch (Exception e) {
                store.db.update(
                    "update outbox_events set retry_count=retry_count+1 where sequence=?",
                    row.get("sequence"));
                metrics.counter("outbox_publish_failures_total").increment();
                break;
              }
            }
            return null;
          });
    } catch (Exception e) {
      org.slf4j.LoggerFactory.getLogger(getClass())
          .warn("Outbox publication failed: {}", e.toString());
    }
  }

  @KafkaListener(
      topics = {
        "engineering.events",
        "production-order.events",
        "material.events",
        "production-planning.events",
        "shop-floor.events",
        "quality-control.events",
        "equipment.events",
        "legacy.events"
      },
      autoStartup = "${app.consumer-enabled:true}")
  public void consume(ConsumerRecord<String, String> record, Acknowledgment ack) {
    Exception failure = null;
    for (int attempt = 0; attempt < 4; attempt++) {
      try {
        Event event = D.JSON.readValue(record.value(), Event.class);
        event.validate();
        if (event.source().equals(service)) {
          ack.acknowledge();
          return;
        }
        Context.set(event.correlationId());
        store.atomic(
            () -> {
              // The inbox claim rolls back with any failing capability handler.
              if (store.claim(event.eventId(), service)) {
                for (Capability cap : caps) {
                  if (store.owner(cap.name()).equals("LOCAL")) cap.on(event);
                  else if (store.owner(cap.name()).equals("FROZEN"))
                    throw new Fault(503, "FROZEN", "Migration in progress");
                }
              }
              return null;
            });
        metrics.counter("kafka_messages_processed_total").increment();
        ack.acknowledge();
        return;
      } catch (Exception e) {
        failure = e;
        try {
          Thread.sleep(100L * (1L << attempt));
        } catch (InterruptedException ex) {
          Thread.currentThread().interrupt();
          throw new IllegalStateException(ex);
        }
      } finally {
        Context.clear();
      }
    }
    try {
      var dead =
          D.obj()
              .put("originalTopic", record.topic())
              .put("partition", record.partition())
              .put("offset", record.offset())
              .put("failure", failure == null ? "unknown" : failure.toString())
              .put("record", record.value());
      // A failed DLQ send must leave the original offset available for redelivery.
      kafka.send(record.topic() + ".dlq", record.key(), dead.toString()).get(10, TimeUnit.SECONDS);
      metrics.counter("kafka_messages_failed_total").increment();
      ack.acknowledge();
    } catch (Exception e) {
      throw new IllegalStateException("DLQ unavailable; offset not acknowledged", e);
    }
  }
}
