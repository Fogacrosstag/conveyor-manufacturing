package com.conveyor.engineering;

import com.conveyor.platform.*;
import com.fasterxml.jackson.databind.node.*;
import java.util.*;
import org.springframework.stereotype.Component;

@Component
public class Engineering implements Capability {
  private final Store s;
  private final Bus bus;
  private final Gateway gateway;

  public Engineering(Store s, Bus b, Gateway g) {
    this.s = s;
    bus = b;
    gateway = g;
  }

  public String name() {
    return "engineering";
  }

  public String prefix() {
    return "/engineering";
  }

  public Set<String> kinds() {
    return Set.of("specification", "bom");
  }

  public Object execute(String method, String path, ObjectNode b) {
    String[] p = path.split("/");
    D.check(p.length >= 3, "Resource required");
    String kind =
        p[2].equals("boms") ? "bom" : p[2].equals("specifications") ? "specification" : "";
    D.check(!kind.isEmpty(), "Unknown engineering resource");
    if (method.equals("GET")) {
      if (p.length == 3) {
        ObjectNode out = D.obj();
        out.set("items", D.JSON.valueToTree(s.all(kind)));
        return out;
      }
      return s.get(kind, p[3]);
    }
    if (p.length == 3) {
      String product = D.required(b, "productId");
      gateway.get("products", "/products/" + product);
      int revision = D.integer(b, "revision");
      D.state(
          s.all(kind).stream()
              .noneMatch(
                  x ->
                      D.s(x, "productId").equals(product)
                          && x.path("revision").asInt() == revision),
          "Revision already exists");
      ObjectNode doc = b.deepCopy();
      if (kind.equals("specification")) {
        ArrayNode checks = D.array(b, "checks");
        D.check(checks.size() == 5, "Exactly five mandatory inspection definitions required");
        Set<String> types = new HashSet<>();
        for (var check : checks) {
          D.check(
              Set.of(
                      "DIMENSIONAL_INSPECTION",
                      "WELDING_INSPECTION",
                      "ELECTRICAL_INSPECTION",
                      "FUNCTIONAL_TEST",
                      "FINAL_INSPECTION")
                  .contains(D.required(check, "type")),
              "Unknown inspection type");
          D.check(types.add(D.s(check, "type")), "Duplicate inspection type");
          D.check(
              D.num(check, "minimum").compareTo(D.num(check, "maximum")) <= 0,
              "Invalid tolerance range");
        }
      } else {
        ObjectNode spec = s.get("specification", D.required(b, "specificationId"));
        D.state(
            D.is(spec, "APPROVED") && D.s(spec, "productId").equals(product),
            "Approved specification for same product required");
        ArrayNode items = D.array(b, "items");
        D.check(!items.isEmpty(), "BOM cannot be empty");
        Set<String> ids = new HashSet<>();
        for (var item : items) {
          String material = D.required(item, "materialId");
          D.positive(item, "quantity");
          D.check(ids.add(material), "Duplicate BOM material");
          ObjectNode m = gateway.get("material", "/materials/" + material);
          D.check(
              D.required(item, "unitOfMeasure").equals(D.s(m, "unitOfMeasure")),
              "Material unit mismatch");
        }
        doc.set("checks", spec.get("checks").deepCopy());
      }
      D.status(doc, "DRAFT");
      ObjectNode created = s.create(kind, doc);
      bus.emit(
          kind.equals("bom") ? "BillOfMaterialsCreated" : "EngineeringSpecificationCreated",
          D.s(created, "id"),
          created);
      return created;
    }
    ObjectNode doc = s.get(kind, p[3]);
    if (p.length == 5 && p[4].equals("approve")) {
      if (D.is(doc, "APPROVED")) return doc;
      D.state(D.is(doc, "DRAFT"), "Only a draft can be approved");
      D.status(doc, "APPROVED").put("approvedAt", java.time.Instant.now().toString());
      s.put(kind, p[3], doc);
      bus.emit(
          kind.equals("bom") ? "BillOfMaterialsApproved" : "EngineeringSpecificationApproved",
          p[3],
          doc);
      return doc;
    }
    throw new Fault(
        405,
        "METHOD_NOT_ALLOWED",
        "Create a new revision instead of changing an existing specification or BOM");
  }
}
