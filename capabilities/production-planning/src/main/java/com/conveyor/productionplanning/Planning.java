package com.conveyor.productionplanning;

import com.conveyor.platform.*;
import com.fasterxml.jackson.databind.node.*;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Component;

@Component
public class Planning implements Capability {
  private final Store s;
  private final Bus bus;

  public Planning(Store s, Bus b) {
    this.s = s;
    bus = b;
  }

  public String name() {
    return "production-planning";
  }

  public String prefix() {
    return "/planning";
  }

  public Set<String> kinds() {
    return Set.of("work-center", "planning-queue", "schedule", "planning-cancelled");
  }

  public static int priority(String value) {
    return switch (value) {
      case "HIGH" -> 3;
      case "NORMAL" -> 2;
      default -> 1;
    };
  }

  public Object execute(String method, String path, ObjectNode b) {
    String[] p = path.split("/");
    D.check(p.length >= 3, "Planning resource required");
    if (method.equals("GET")) {
      String kind = p[2].equals("work-centers") ? "work-center" : "schedule";
      if (p.length == 3) {
        ObjectNode out = D.obj();
        out.set("items", D.JSON.valueToTree(s.all(kind)));
        return out;
      }
      return s.get(kind, p[3]);
    }
    if (p[2].equals("work-centers") && p.length == 3) {
      D.only(
          b,
          "id",
          "code",
          "name",
          "sequence",
          "durationMinutes",
          "capacity",
          "equipmentId",
          "recoveryAllowanceMinutes");
      D.required(b, "code");
      D.integer(b, "sequence");
      D.integer(b, "durationMinutes");
      D.integer(b, "capacity");
      D.integer(b, "recoveryAllowanceMinutes");
      D.required(b, "equipmentId");
      D.state(
          s.all("work-center").stream()
              .noneMatch(
                  x ->
                      D.s(x, "code").equals(D.s(b, "code"))
                          || x.path("sequence").asInt() == b.path("sequence").asInt()),
          "Duplicate work-center code or sequence");
      return s.create("work-center", b.deepCopy().put("status", "AVAILABLE"));
    }
    if (p[2].equals("dispatch")) {
      var queue =
          s.all("planning-queue").stream()
              .filter(x -> D.is(x, "QUEUED"))
              .sorted(
                  Comparator.<ObjectNode>comparingInt(x -> -priority(D.s(x, "priority")))
                      .thenComparing(x -> D.s(x, "createdAt")))
              .toList();
      ArrayNode results = D.JSON.createArrayNode();
      for (ObjectNode order : queue) {
        ObjectNode schedule = allocate(order);
        if (schedule != null) results.add(schedule);
      }
      ObjectNode out = D.obj();
      out.set("items", results);
      return out;
    }
    throw new Fault(405, "METHOD_NOT_ALLOWED", "Use dispatch to allocate pending orders");
  }

  private ObjectNode allocate(ObjectNode order) {
    String id = D.s(order, "productionOrderId");
    var centers =
        s.all("work-center").stream()
            .sorted(Comparator.comparingInt(x -> x.path("sequence").asInt()))
            .toList();
    D.state(centers.size() == 7, "Configure seven work centers before dispatch");
    if (centers.stream().anyMatch(x -> !D.is(x, "AVAILABLE"))) return null;
    Instant cursor = Instant.now();
    ObjectNode schedule = order.deepCopy().put("id", id).put("status", "APPROVED");
    ArrayNode tasks = schedule.putArray("tasks");
    int sequence = 0;
    for (ObjectNode center : centers) {
      Instant best = null;
      int bestLane = 0;
      for (int lane = 0; lane < center.path("capacity").asInt(); lane++) {
        Instant available = cursor;
        for (ObjectNode other : s.all("schedule")) {
          if (D.is(other, "CANCELLED")) continue;
          for (var task : other.path("tasks")) {
            if (D.s(task, "workCenterId").equals(D.s(center, "id"))
                && task.path("lane").asInt() == lane) {
              Instant end = Instant.parse(D.s(task, "plannedEndTime"));
              if (end.isAfter(available)) available = end;
            }
          }
        }
        if (best == null || available.isBefore(best)) {
          best = available;
          bestLane = lane;
        }
      }
      Instant end = best.plusSeconds(center.path("durationMinutes").asLong() * 60);
      tasks.add(
          D.obj()
              .put("id", UUID.randomUUID().toString())
              .put("productionOrderId", id)
              .put("workCenterId", D.s(center, "id"))
              .put("equipmentId", D.s(center, "equipmentId"))
              .put("operationCode", D.s(center, "code"))
              .put("sequence", ++sequence)
              .put("lane", bestLane)
              .put("plannedStartTime", best.toString())
              .put("plannedEndTime", end.toString()));
      cursor = end;
    }
    s.put("schedule", id, schedule);
    D.status(order, "DISPATCHED");
    s.put("planning-queue", id, order);
    bus.emit("ProductionScheduleApproved", id, schedule);
    return schedule;
  }

  public void on(Event e) {
    String id = D.s(e.payload(), "productionOrderId");
    switch (e.eventType()) {
      case "MaterialReserved":
        if (s.find("planning-cancelled", id).isEmpty() && s.find("planning-queue", id).isEmpty())
          s.put("planning-queue", id, e.payload().deepCopy().put("status", "QUEUED"));
        break;
      case "ProductionOrderCancelled":
        s.put("planning-cancelled", id, D.obj().put("id", id));
        s.find("planning-queue", id)
            .ifPresent(
                q -> {
                  D.status(q, "CANCELLED");
                  s.put("planning-queue", id, q);
                });
        s.find("schedule", id)
            .ifPresent(
                q -> {
                  D.status(q, "CANCELLED");
                  s.put("schedule", id, q);
                });
        break;
      case "EquipmentStateChanged":
        {
          String wc = D.s(e.payload(), "workCenterId");
          ObjectNode center = s.get("work-center", wc);
          long seq = e.payload().path("sequence").asLong();
          if (seq <= center.path("equipmentSequence").asLong()) return;
          center.put("equipmentSequence", seq);
          boolean down = !D.s(e.payload(), "equipmentStatus").equals("AVAILABLE");
          D.status(center, down ? "UNAVAILABLE" : "AVAILABLE");
          s.put("work-center", wc, center);
          if (down) {
            long seconds = center.path("recoveryAllowanceMinutes").asLong() * 60;
            Instant boundary = Instant.now();
            for (ObjectNode schedule : s.all("schedule")) {
              if (D.is(schedule, "CANCELLED")) continue;
              boolean touched = false;
              for (var t : schedule.path("tasks")) {
                ObjectNode task = (ObjectNode) t;
                if (!Instant.parse(D.s(task, "plannedEndTime")).isBefore(boundary)) {
                  task.put(
                      "plannedStartTime",
                      Instant.parse(D.s(task, "plannedStartTime")).plusSeconds(seconds).toString());
                  task.put(
                      "plannedEndTime",
                      Instant.parse(D.s(task, "plannedEndTime")).plusSeconds(seconds).toString());
                  touched = true;
                }
              }
              if (touched) {
                schedule.put("delayed", true);
                s.put("schedule", D.s(schedule, "id"), schedule);
                bus.emit("ProductionScheduleChanged", D.s(schedule, "productionOrderId"), schedule);
              }
            }
          }
          break;
        }
      default:
        break;
    }
  }
}
