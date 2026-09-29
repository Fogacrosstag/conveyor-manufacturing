package com.conveyor.platform;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class Bus {
  private final Store store;
  private final ObjectProvider<Capability> capabilities;
  private final boolean baseline;
  private final String service;

  public Bus(
      Store store,
      ObjectProvider<Capability> capabilities,
      @Value("${app.baseline:false}") boolean baseline,
      @Value("${spring.application.name}") String service) {
    this.store = store;
    this.capabilities = capabilities;
    this.baseline = baseline;
    this.service = service;
  }

  public void emit(String type, String id, ObjectNode payload) {
    Event e = Event.create(type, service, id, payload);
    ObjectNode json = D.of(e);
    String topic = baseline ? "legacy.events" : service.replace("-service", "") + ".events";
    store.db.update(
        "insert into outbox_events(event_id,aggregate_id,topic,payload) values (?,?,?,?::jsonb)",
        e.eventId(),
        id,
        topic,
        json.toString());
    // Baseline handlers share the caller transaction; extracted owners receive Kafka events.
    if (baseline) {
      for (Capability c : capabilities.orderedStream().toList()) {
        if (store.owner(c.name()).equals("LOCAL")) c.on(e);
      }
    }
  }
}
