package com.conveyor.legacy;

import com.conveyor.platform.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.*;
import org.springframework.stereotype.Component;

@Component
public class LegacyShopFloor implements Capability {
  private final Store s;
  private final Bus bus;

  public LegacyShopFloor(Store s, Bus b) {
    this.s = s;
    bus = b;
  }

  public String name() {
    return "shop-floor";
  }

  public String prefix() {
    return "/shop-floor";
  }

  public Set<String> kinds() {
    return Set.of("operation", "shop-order", "equipment", "equipment-observation");
  }

  public Object execute(String method, String path, ObjectNode b) {
    String[] p = path.split("/");
    if (method.equals("GET")) {
      if (p.length == 3) {
        ObjectNode out = D.obj();
        out.set("items", D.JSON.valueToTree(s.all("operation")));
        return out;
      }
      return s.get("operation", p[3]);
    }
    if (p.length == 3 && p[2].equals("events")) {
      equipment(b);
      return D.obj().put("accepted", true);
    }
    D.check(p.length == 5, "Operation action required");
    ObjectNode op = s.get("operation", p[3]);
    String order = D.s(op, "productionOrderId");
    ObjectNode authorization = s.get("shop-order", order);
    D.state(!D.is(authorization, "CANCELLED"), "Order cancelled");
    switch (p[4]) {
      case "start":
        D.state(authorization.path("authorized").asBoolean(), "Order start authorization required");
        D.state(D.is(op, "PENDING"), "Operation is not pending");
        D.state(
            s.all("operation").stream()
                .filter(
                    x ->
                        D.s(x, "productionOrderId").equals(order)
                            && x.path("sequence").asInt() < op.path("sequence").asInt())
                .allMatch(x -> D.is(x, "COMPLETED")),
            "Predecessor not completed");
        available(op);
        D.status(op, "IN_PROGRESS").put("actualStartTime", java.time.Instant.now().toString());
        break;
      case "pause":
        D.state(D.is(op, "IN_PROGRESS"), "Operation is not active");
        D.status(op, "PAUSED");
        break;
      case "resume":
        D.state(D.is(op, "PAUSED"), "Operation is not paused");
        available(op);
        D.status(op, "IN_PROGRESS");
        break;
      case "complete":
        if (D.is(op, "COMPLETED")) return op;
        D.state(D.is(op, "IN_PROGRESS"), "Operation is not active");
        D.check(
            b.path("processedQuantity").isIntegralNumber()
                && b.path("rejectedQuantity").isIntegralNumber(),
            "Quantities must be integers");
        int processed = b.path("processedQuantity").asInt(-1),
            rejected = b.path("rejectedQuantity").asInt(-1);
        D.check(
            processed >= 0
                && rejected >= 0
                && processed + rejected == authorization.path("quantity").asInt(),
            "Processed and rejected quantities must equal order quantity");
        D.check(rejected == 0, "Rejected units require rework before completing this operation");
        op.put("processedQuantity", processed)
            .put("rejectedQuantity", rejected)
            .put("actualCompletionTime", java.time.Instant.now().toString());
        D.status(op, "COMPLETED");
        break;
      default:
        throw new Fault(404, "NOT_FOUND", "Unknown operation action");
    }
    s.put("operation", p[3], op);
    String event =
        switch (p[4]) {
          case "start" -> "ManufacturingOperationStarted";
          case "pause" -> "ManufacturingOperationPaused";
          case "resume" -> "ManufacturingOperationResumed";
          default -> "ManufacturingOperationCompleted";
        };
    bus.emit(event, order, op);
    if (s.all("operation").stream()
        .filter(x -> D.s(x, "productionOrderId").equals(order))
        .allMatch(x -> D.is(x, "COMPLETED")))
      bus.emit("AllManufacturingOperationsCompleted", order, authorization);
    return op;
  }

  private void available(ObjectNode op) {
    D.state(
        s.all("operation").stream()
            .noneMatch(
                x ->
                    !D.s(x, "id").equals(D.s(op, "id"))
                        && D.s(x, "workCenterId").equals(D.s(op, "workCenterId"))
                        && x.path("lane").asInt() == op.path("lane").asInt()
                        && (D.is(x, "IN_PROGRESS") || D.is(x, "PAUSED"))),
        "Work-center lane is occupied");
    s.find("equipment", D.s(op, "equipmentId"))
        .ifPresent(e -> D.state(D.is(e, "AVAILABLE"), "Equipment unavailable"));
  }

  private void equipment(ObjectNode b) {
    String eventId = D.required(b, "eventId");
    UUID.fromString(eventId);
    if (s.find("equipment-observation", eventId).isPresent()) return;
    String id = D.required(b, "equipmentId");
    D.check(
        s.all("operation").stream()
            .anyMatch(
                x ->
                    D.s(x, "equipmentId").equals(id)
                        && D.s(x, "workCenterId").equals(D.s(b, "workCenterId"))),
        "Unknown equipment/work-center pair");
    int sequence = D.integer(b, "sequence");
    String type = D.required(b, "eventType");
    D.check(
        Set.of(
                "EQUIPMENT_STARTED",
                "EQUIPMENT_STOPPED",
                "EQUIPMENT_FAILURE",
                "EQUIPMENT_RECOVERED",
                "PRODUCTION_CYCLE_COMPLETED",
                "MAINTENANCE_REQUIRED")
            .contains(type),
        "Unknown equipment event");
    s.put("equipment-observation", eventId, b);
    ObjectNode equipment = s.find("equipment", id).orElse(D.obj().put("id", id).put("sequence", 0));
    if (sequence <= equipment.path("sequence").asInt()) return;
    boolean down =
        Set.of("EQUIPMENT_STOPPED", "EQUIPMENT_FAILURE", "MAINTENANCE_REQUIRED").contains(type);
    boolean observationOnly =
        type.equals("PRODUCTION_CYCLE_COMPLETED") || type.equals("EQUIPMENT_STARTED");
    String state = observationOnly ? D.s(equipment, "status") : down ? "UNAVAILABLE" : "AVAILABLE";
    if (state.isEmpty()) state = "AVAILABLE";
    String previous = D.s(equipment, "status");
    if (state.equals("UNAVAILABLE") && !previous.equals("UNAVAILABLE"))
      equipment.put("unavailableSince", java.time.Instant.now().toString());
    if (state.equals("AVAILABLE")
        && previous.equals("UNAVAILABLE")
        && equipment.has("unavailableSince")) {
      double seconds =
          java.time.Duration.between(
                      java.time.Instant.parse(D.s(equipment, "unavailableSince")),
                      java.time.Instant.now())
                  .toMillis()
              / 1000.0;
      equipment.put("downtimeSeconds", equipment.path("downtimeSeconds").asDouble() + seconds);
      equipment.remove("unavailableSince");
    }
    equipment.put("sequence", sequence);
    D.status(equipment, state);
    s.put("equipment", id, equipment);
    if (state.equals("UNAVAILABLE")) {
      for (ObjectNode op : s.all("operation")) {
        if (D.s(op, "equipmentId").equals(id) && D.is(op, "IN_PROGRESS")) {
          D.status(op, "PAUSED");
          s.put("operation", D.s(op, "id"), op);
          bus.emit("ManufacturingOperationPaused", D.s(op, "productionOrderId"), op);
        }
      }
    }
    ObjectNode payload = b.deepCopy().put("equipmentStatus", state);
    bus.emit("EquipmentStateChanged", id, payload);
  }

  public void on(Event e) {
    String id = D.s(e.payload(), "productionOrderId");
    switch (e.eventType()) {
      case "ProductionOrderStarted":
        {
          ObjectNode order = s.find("shop-order", id).orElse(e.payload().deepCopy());
          if (!D.is(order, "CANCELLED")) {
            order.put("authorized", true);
            s.put("shop-order", id, order);
          }
          break;
        }
      case "ProductionOrderCancelled":
        {
          ObjectNode order = s.find("shop-order", id).orElse(e.payload().deepCopy());
          D.status(order, "CANCELLED");
          s.put("shop-order", id, order);
          for (ObjectNode op : s.all("operation"))
            if (D.s(op, "productionOrderId").equals(id)) {
              D.status(op, "CANCELLED");
              s.put("operation", D.s(op, "id"), op);
            }
          break;
        }
      case "ProductionScheduleApproved":
        {
          ObjectNode order = s.find("shop-order", id).orElse(e.payload().deepCopy());
          if (D.is(order, "CANCELLED")) return;
          s.put("shop-order", id, order);
          for (var task : e.payload().path("tasks")) {
            String opId = D.s(task, "id");
            if (s.find("operation", opId).isEmpty())
              s.put("operation", opId, ((ObjectNode) task).deepCopy().put("status", "PENDING"));
          }
          break;
        }
      case "ProductionScheduleChanged":
        for (var task : e.payload().path("tasks")) {
          s.find("operation", D.s(task, "id"))
              .ifPresent(
                  op -> {
                    op.put("plannedStartTime", D.s(task, "plannedStartTime"))
                        .put("plannedEndTime", D.s(task, "plannedEndTime"));
                    s.put("operation", D.s(op, "id"), op);
                  });
        }
        break;
      default:
        break;
    }
  }
}
