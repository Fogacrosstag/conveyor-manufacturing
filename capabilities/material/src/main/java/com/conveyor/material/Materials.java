package com.conveyor.material;

import com.conveyor.platform.*;
import com.fasterxml.jackson.databind.node.*;
import java.math.BigDecimal;
import java.util.*;
import org.springframework.stereotype.Component;

@Component
public class Materials implements Capability {
  private final Store s;
  private final Bus bus;

  public Materials(Store s, Bus b) {
    this.s = s;
    bus = b;
  }

  public String name() {
    return "material";
  }

  public String prefix() {
    return "/materials";
  }

  public Set<String> kinds() {
    return Set.of("material", "reservation", "material-cancelled");
  }

  public static boolean canReserve(BigDecimal hand, BigDecimal reserved, BigDecimal need) {
    return need.signum() > 0 && hand.subtract(reserved).compareTo(need) >= 0;
  }

  private void stock(ObjectNode m, BigDecimal hand, BigDecimal reserved) {
    D.state(
        hand.signum() >= 0 && reserved.signum() >= 0 && hand.compareTo(reserved) >= 0,
        "Stock invariant violation");
    m.put("quantityOnHand", hand)
        .put("reservedQuantity", reserved)
        .put("availableQuantity", hand.subtract(reserved));
    s.put("material", D.s(m, "id"), m);
  }

  public Object execute(String method, String path, ObjectNode b) {
    String[] p = path.split("/");
    if (method.equals("GET")) {
      if (p.length == 2) {
        ObjectNode out = D.obj();
        out.set("items", D.JSON.valueToTree(s.all("material")));
        return out;
      }
      if (p[2].equals("reservations")) return s.get("reservation", p[3]);
      if (p[2].equals("low-stock")) {
        ObjectNode out = D.obj();
        out.set(
            "items",
            D.JSON.valueToTree(
                s.all("material").stream()
                    .filter(
                        x ->
                            D.num(x, "availableQuantity").compareTo(D.num(x, "minimumStockLevel"))
                                < 0)
                    .toList()));
        return out;
      }
      return s.get("material", p[2]);
    }
    if (p.length == 2) {
      D.only(b, "id", "materialCode", "name", "unitOfMeasure", "minimumStockLevel");
      D.required(b, "name");
      String code = D.required(b, "materialCode");
      D.required(b, "unitOfMeasure");
      D.check(D.num(b, "minimumStockLevel").signum() >= 0, "Threshold cannot be negative");
      D.state(
          s.all("material").stream().noneMatch(x -> D.s(x, "materialCode").equals(code)),
          "Material code already exists");
      ObjectNode m =
          b.deepCopy()
              .put("quantityOnHand", 0)
              .put("reservedQuantity", 0)
              .put("availableQuantity", 0);
      return s.create("material", m);
    }
    if (p.length == 3 && p[2].equals("receive")) {
      ObjectNode m = s.get("material", D.required(b, "materialId"));
      stock(
          m,
          D.num(m, "quantityOnHand").add(D.positive(b, "quantity")),
          D.num(m, "reservedQuantity"));
      bus.emit("MaterialReceived", D.s(m, "id"), m);
      return m;
    }
    throw new Fault(
        405,
        "METHOD_NOT_ALLOWED",
        "Reservations and consumption are authorized by production events");
  }

  public void on(Event e) {
    String type = e.eventType();
    if (!Set.of(
            "ProductionOrderSubmitted",
            "ProductionOrderCancelled",
            "AllManufacturingOperationsCompleted")
        .contains(type)) return;
    ObjectNode payload = e.payload();
    String id = D.required(payload, "productionOrderId");
    if (type.equals("ProductionOrderCancelled")) {
      s.put("material-cancelled", id, D.obj().put("id", id));
      s.find("reservation", id)
          .ifPresent(
              r -> {
                if (D.is(r, "RESERVED")) {
                  adjust(r, false);
                  D.status(r, "RELEASED");
                  s.put("reservation", id, r);
                  bus.emit("MaterialReservationReleased", id, r);
                }
              });
      return;
    }
    if (s.find("material-cancelled", id).isPresent()) return;
    if (type.equals("ProductionOrderSubmitted")) {
      var existing = s.find("reservation", id);
      if (existing.isPresent()
          && (D.is(existing.get(), "CONSUMED") || D.is(existing.get(), "RELEASED"))) return;
      if (existing.isPresent() && D.is(existing.get(), "RESERVED")) {
        bus.emit("MaterialReserved", id, existing.get());
        return;
      }
      ArrayNode needs = D.array(payload, "requirements");
      for (var need : needs) {
        ObjectNode m = s.get("material", D.required(need, "materialId"));
        if (!canReserve(
            D.num(m, "quantityOnHand"),
            D.num(m, "reservedQuantity"),
            D.positive(need, "quantity"))) {
          ObjectNode failed =
              payload.deepCopy().put("reason", "Insufficient stock for " + D.s(m, "materialCode"));
          bus.emit("MaterialReservationFailed", id, failed);
          return;
        }
      }
      for (var need : needs) {
        ObjectNode m = s.get("material", D.s(need, "materialId"));
        stock(
            m,
            D.num(m, "quantityOnHand"),
            D.num(m, "reservedQuantity").add(D.num(need, "quantity")));
      }
      ObjectNode reservation = payload.deepCopy().put("id", id).put("status", "RESERVED");
      s.put("reservation", id, reservation);
      bus.emit("MaterialReserved", id, reservation);
      return;
    }
    ObjectNode r = s.get("reservation", id);
    if (D.is(r, "CONSUMED")) return;
    D.state(D.is(r, "RESERVED"), "Reservation is not consumable");
    adjust(r, true);
    D.status(r, "CONSUMED");
    s.put("reservation", id, r);
    bus.emit("MaterialConsumed", id, r);
  }

  // Consumption reduces on-hand and reserved balances; release reduces only the reservation.
  private void adjust(ObjectNode r, boolean consume) {
    for (var need : r.path("requirements")) {
      ObjectNode m = s.get("material", D.s(need, "materialId"));
      BigDecimal amount = D.num(need, "quantity");
      stock(
          m,
          D.num(m, "quantityOnHand").subtract(consume ? amount : BigDecimal.ZERO),
          D.num(m, "reservedQuantity").subtract(amount));
    }
  }
}
