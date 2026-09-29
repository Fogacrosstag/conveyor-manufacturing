package com.conveyor.legacy;

import com.conveyor.platform.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.*;
import org.springframework.stereotype.Component;

@Component
public class LegacyAudit implements Capability {
  private final Store s;

  public LegacyAudit(Store s) {
    this.s = s;
  }

  public String name() {
    return "manufacturing-audit";
  }

  public String prefix() {
    return "/audit";
  }

  public Set<String> kinds() {
    return Set.of("audit-event");
  }

  public Object execute(String method, String path, ObjectNode body) {
    D.check(method.equals("GET"), "Audit is read only");
    String[] p = path.split("/");
    ObjectNode out = D.obj();
    out.set(
        "items",
        D.JSON.valueToTree(
            s.all("audit-event").stream()
                .filter(
                    x -> p.length < 4 || D.s(x.path("payload"), "productionOrderId").equals(p[3]))
                .limit(500)
                .toList()));
    return out;
  }

  public void on(Event e) {
    s.put("audit-event", e.eventId(), D.of(e));
  }
}
