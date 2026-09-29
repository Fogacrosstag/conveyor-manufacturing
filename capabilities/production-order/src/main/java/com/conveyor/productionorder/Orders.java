package com.conveyor.productionorder;

import com.conveyor.platform.*;
import com.fasterxml.jackson.databind.node.*;
import java.math.BigDecimal;
import java.util.*;
import org.springframework.stereotype.Component;

@Component
public class Orders implements Capability {
  private final Store s;
  private final Bus bus;
  private final Gateway gateway;

  public Orders(Store s, Bus bus, Gateway g) {
    this.s = s;
    this.bus = bus;
    gateway = g;
  }

  public String name() {
    return "production-order";
  }

  public String prefix() {
    return "/production-orders";
  }

  public Set<String> kinds() {
    return Set.of("order");
  }

  private void save(ObjectNode d, String status) {
    String previous = D.s(d, "status");
    if (!previous.equals(status)) {
      ArrayNode history = d.withArray("history");
      history.add(
          D.obj()
              .put("from", previous)
              .put("to", status)
              .put("at", java.time.Instant.now().toString()));
      D.status(d, status);
    }
    s.put("order", D.s(d, "id"), d);
  }

  public Object execute(String method, String path, ObjectNode b) {
    String[] p = path.split("/");
    if (method.equals("GET")) {
      if (p.length == 2) {
        ObjectNode out = D.obj();
        out.set("items", D.JSON.valueToTree(s.all("order")));
        return out;
      }
      return s.get("order", p[2]);
    }
    if (p.length == 2) {
      D.only(
          b,
          "id",
          "productId",
          "bomRevisionId",
          "quantity",
          "priority",
          "plannedStartDate",
          "plannedCompletionDate");
      String product = D.required(b, "productId");
      gateway.get("products", "/products/" + product);
      ObjectNode bom =
          gateway.get("engineering", "/engineering/boms/" + D.required(b, "bomRevisionId"));
      D.state(
          D.is(bom, "APPROVED") && D.s(bom, "productId").equals(product),
          "Approved BOM for the requested product required");
      int quantity = D.integer(b, "quantity");
      String priority = D.s(b, "priority");
      D.check(
          Set.of("LOW", "NORMAL", "HIGH").contains(priority),
          "Priority must be LOW, NORMAL or HIGH");
      ObjectNode order = b.deepCopy().put("id", D.id(b)).put("status", "CREATED");
      order
          .put("productionOrderId", D.s(order, "id"))
          .put("orderNumber", "PO-" + D.s(order, "id").substring(0, 8));
      order.set("bomSnapshot", bom.deepCopy());
      order.set("checks", bom.get("checks").deepCopy());
      ArrayNode required = order.putArray("requirements");
      for (var item : bom.path("items")) {
        ObjectNode r = ((ObjectNode) item).deepCopy();
        r.put("quantity", D.num(item, "quantity").multiply(BigDecimal.valueOf(quantity)));
        required.add(r);
      }
      order = s.create("order", order);
      bus.emit("ProductionOrderCreated", D.s(order, "id"), order);
      return order;
    }
    D.check(p.length == 4, "Action required");
    ObjectNode order = s.get("order", p[2]);
    String action = p[3];
    switch (action) {
      case "submit":
        if (!D.is(order, "CREATED") && !D.is(order, "MATERIALS_PENDING"))
          throw new Fault(409, "INVALID_STATE", "Order cannot be submitted in this state");
        save(order, "MATERIALS_PENDING");
        bus.emit("ProductionOrderSubmitted", p[2], order);
        break;
      case "start":
        if (D.is(order, "IN_PROGRESS")) return order;
        D.state(
            D.is(order, "SCHEDULED") && order.path("materialsReserved").asBoolean(),
            "Approved schedule and reserved materials required");
        order.put("actualStartDate", java.time.Instant.now().toString());
        save(order, "IN_PROGRESS");
        bus.emit("ProductionOrderStarted", p[2], order);
        break;
      case "cancel":
        if (D.is(order, "CANCELLED")) return order;
        D.state(
            Set.of("CREATED", "ON_HOLD", "MATERIALS_PENDING", "MATERIALS_RESERVED", "SCHEDULED")
                .contains(D.s(order, "status")),
            "Cannot cancel after production starts");
        save(order, "CANCELLED");
        bus.emit("ProductionOrderCancelled", p[2], order);
        break;
      case "hold":
        D.state(D.is(order, "CREATED"), "Only an unsubmitted order can be held");
        save(order, "ON_HOLD");
        break;
      case "resume":
        D.state(D.is(order, "ON_HOLD"), "Order is not on hold");
        save(order, "CREATED");
        break;
      case "complete":
        D.state(
            order.path("operationsComplete").asBoolean()
                && order.path("qualityApproved").asBoolean()
                && order.path("materialsConsumed").asBoolean(),
            "Completion facts are missing");
        complete(order);
        break;
      default:
        throw new Fault(404, "NOT_FOUND", "Unknown order action");
    }
    return s.get("order", p[2]);
  }

  // Completion facts can arrive on different Kafka topics in any order.
  private void complete(ObjectNode d) {
    if (D.is(d, "COMPLETED") || D.is(d, "CANCELLED")) return;
    if (d.path("operationsComplete").asBoolean()
        && d.path("qualityApproved").asBoolean()
        && d.path("materialsConsumed").asBoolean()) {
      d.put("actualCompletionDate", java.time.Instant.now().toString());
      save(d, "COMPLETED");
      bus.emit("ProductionOrderCompleted", D.s(d, "id"), d);
    }
  }

  public void on(Event e) {
    if (!Set.of(
            "MaterialReserved",
            "MaterialReservationFailed",
            "ProductionScheduleApproved",
            "AllManufacturingOperationsCompleted",
            "FinalQualityApproved",
            "MaterialConsumed")
        .contains(e.eventType())) return;
    ObjectNode d = s.get("order", D.required(e.payload(), "productionOrderId"));
    if (D.is(d, "CANCELLED") || D.is(d, "COMPLETED")) return;
    switch (e.eventType()) {
      case "MaterialReserved":
        d.put("materialsReserved", true);
        break;
      case "MaterialReservationFailed":
        d.put("materialFailure", D.s(e.payload(), "reason"));
        break;
      case "ProductionScheduleApproved":
        d.put("scheduleApproved", true);
        break;
      case "AllManufacturingOperationsCompleted":
        d.put("operationsComplete", true);
        break;
      case "FinalQualityApproved":
        d.put("qualityApproved", true);
        break;
      case "MaterialConsumed":
        d.put("materialsConsumed", true);
        break;
      default:
        break;
    }
    String status = D.s(d, "status");
    if (d.path("operationsComplete").asBoolean()) status = "QUALITY_PENDING";
    else if (!status.equals("IN_PROGRESS")) {
      if (d.path("scheduleApproved").asBoolean() && d.path("materialsReserved").asBoolean())
        status = "SCHEDULED";
      else if (d.path("materialsReserved").asBoolean()) status = "MATERIALS_RESERVED";
    }
    save(d, status);
    complete(d);
  }
}
