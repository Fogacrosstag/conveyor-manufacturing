package com.conveyor.legacy;

import com.conveyor.platform.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.*;
import org.springframework.stereotype.Component;

@Component
public class Products implements Capability {
  private final Store s;

  public Products(Store s) {
    this.s = s;
  }

  public String name() {
    return "products";
  }

  public String prefix() {
    return "/products";
  }

  public Set<String> kinds() {
    return Set.of("product");
  }

  public Object execute(String method, String path, ObjectNode b) {
    String[] p = path.split("/");
    if (method.equals("GET")) {
      if (p.length == 2) {
        ObjectNode out = D.obj();
        out.set("items", D.JSON.valueToTree(s.all("product")));
        return out;
      }
      return s.get("product", p[2]);
    }
    D.check(p.length == 2, "Products are immutable in this demonstration");
    D.only(b, "id", "code", "name", "description", "productType");
    String code = D.required(b, "code");
    D.required(b, "name");
    D.check(
        Set.of(
                "BELT_CONVEYOR",
                "ROLLER_CONVEYOR",
                "CHAIN_CONVEYOR",
                "MODULAR_CONVEYOR",
                "CONVEYOR_COMPONENT")
            .contains(D.required(b, "productType")),
        "Invalid product type");
    D.state(
        s.all("product").stream().noneMatch(x -> D.s(x, "code").equals(code)),
        "Product code exists");
    return s.create("product", b.deepCopy().put("status", "ACTIVE"));
  }
}
