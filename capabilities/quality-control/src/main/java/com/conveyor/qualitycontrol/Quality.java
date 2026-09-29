package com.conveyor.qualitycontrol;

import com.conveyor.platform.*;
import com.fasterxml.jackson.databind.node.*;
import java.util.*;
import org.springframework.stereotype.Component;

@Component
public class Quality implements Capability {
  private final Store s;
  private final Bus bus;

  public Quality(Store s, Bus b) {
    this.s = s;
    bus = b;
  }

  public String name() {
    return "quality-control";
  }

  public String prefix() {
    return "/quality";
  }

  public Set<String> kinds() {
    return Set.of("inspection", "quality-attempt", "quality-order");
  }

  public Object execute(String method, String path, ObjectNode b) {
    String[] p = path.split("/");
    D.check(p.length >= 3, "Quality resource required");
    if (method.equals("GET")) {
      if (p[2].equals("production-orders")) {
        ObjectNode out = D.obj();
        out.set(
            "items",
            D.JSON.valueToTree(
                s.all("inspection").stream()
                    .filter(x -> D.s(x, "productionOrderId").equals(p[3]))
                    .toList()));
        return out;
      }
      if (p.length == 3) {
        ObjectNode out = D.obj();
        out.set("items", D.JSON.valueToTree(s.all("inspection")));
        return out;
      }
      return s.get("inspection", p[3]);
    }
    D.check(p.length == 5, "Inspection action required");
    ObjectNode inspection = s.get("inspection", p[3]);
    switch (p[4]) {
      case "start":
        D.state(D.is(inspection, "PENDING"), "Inspection already started");
        D.status(inspection, "IN_PROGRESS");
        break;
      case "checks":
        D.state(D.is(inspection, "IN_PROGRESS"), "Start inspection before recording measurements");
        inspection.put("measuredValue", D.num(b, "measuredValue"));
        inspection.put("inspectorId", D.required(b, "inspectorId"));
        break;
      case "complete":
        if (D.is(inspection, "COMPLETED")) return inspection;
        D.state(
            D.is(inspection, "IN_PROGRESS") && inspection.has("measuredValue"),
            "Measurement required");
        boolean pass =
            D.num(inspection, "measuredValue").compareTo(D.num(inspection, "minimum")) >= 0
                && D.num(inspection, "measuredValue").compareTo(D.num(inspection, "maximum")) <= 0;
        inspection
            .put("result", pass ? "PASSED" : "FAILED")
            .put("inspectedAt", java.time.Instant.now().toString());
        D.status(inspection, "COMPLETED");
        s.put(
            "quality-attempt",
            p[3] + ":" + inspection.path("attempt").asInt(),
            inspection.deepCopy());
        s.put("inspection", p[3], inspection);
        bus.emit(
            pass ? "QualityInspectionPassed" : "ManufacturingReworkRequired",
            D.s(inspection, "productionOrderId"),
            inspection);
        approve(D.s(inspection, "productionOrderId"));
        return inspection;
      case "retry":
        D.state(
            D.is(inspection, "COMPLETED") && D.s(inspection, "result").equals("FAILED"),
            "Only failed inspections can be retried");
        inspection
            .put("reworkNote", D.required(b, "reworkNote"))
            .put("attempt", inspection.path("attempt").asInt() + 1);
        inspection.remove(List.of("result", "measuredValue", "inspectorId", "inspectedAt"));
        D.status(inspection, "PENDING");
        break;
      default:
        throw new Fault(404, "NOT_FOUND", "Unknown inspection action");
    }
    s.put("inspection", p[3], inspection);
    return inspection;
  }

  public void on(Event e) {
    if (!e.eventType().equals("AllManufacturingOperationsCompleted")) return;
    String order = D.required(e.payload(), "productionOrderId");
    if (s.find("quality-order", order).isPresent()) return;
    s.put("quality-order", order, e.payload().deepCopy());
    for (var check : D.array(e.payload(), "checks")) {
      ObjectNode inspection =
          ((ObjectNode) check)
              .deepCopy()
              .put("id", UUID.randomUUID().toString())
              .put("productionOrderId", order)
              .put("status", "PENDING")
              .put("attempt", 1);
      s.put("inspection", D.s(inspection, "id"), inspection);
      bus.emit("QualityInspectionCreated", order, inspection);
    }
  }

  private void approve(String order) {
    var inspections =
        s.all("inspection").stream()
            .filter(x -> D.s(x, "productionOrderId").equals(order))
            .toList();
    ObjectNode d = s.get("quality-order", order);
    if (!d.path("approved").asBoolean()
        && inspections.size() == 5
        && inspections.stream().allMatch(x -> D.s(x, "result").equals("PASSED"))) {
      d.put("approved", true);
      s.put("quality-order", order, d);
      bus.emit("FinalQualityApproved", order, d);
    }
  }
}
