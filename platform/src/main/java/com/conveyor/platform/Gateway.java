package com.conveyor.platform;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class Gateway {
  private final ObjectProvider<Capability> caps;
  private final Store store;
  private final boolean baseline;
  private final HttpClient client =
      HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();

  public Gateway(
      ObjectProvider<Capability> caps,
      Store store,
      @Value("${app.baseline:false}") boolean baseline) {
    this.caps = caps;
    this.store = store;
    this.baseline = baseline;
  }

  public String url(String cap) {
    return System.getenv()
        .getOrDefault(
            cap.toUpperCase().replace('-', '_') + "_URL",
            "http://" + (cap.equals("products") ? "legacy-monolith" : cap + "-service") + ":8080");
  }

  public ObjectNode get(String cap, String path) {
    if (baseline && store.owner(cap).equals("LOCAL")) {
      Capability c = caps.stream().filter(x -> x.name().equals(cap)).findFirst().orElseThrow();
      return D.of(c.execute("GET", path, D.obj()));
    }
    return call(cap, "GET", path, D.obj(), "");
  }

  public ObjectNode call(String cap, String method, String path, ObjectNode body, String key) {
    try {
      HttpRequest.Builder b =
          HttpRequest.newBuilder(URI.create(url(cap) + path))
              .timeout(Duration.ofSeconds(8))
              .header("Content-Type", "application/json")
              .header("X-Correlation-ID", Context.id());
      if (!key.isBlank()) b.header("Idempotency-Key", key);
      b.method(
          method,
          method.equals("GET")
              ? HttpRequest.BodyPublishers.noBody()
              : HttpRequest.BodyPublishers.ofString(body.toString()));
      HttpResponse<String> r = client.send(b.build(), HttpResponse.BodyHandlers.ofString());
      ObjectNode out = D.parse(r.body());
      if (r.statusCode() >= 400)
        throw new Fault(r.statusCode(), D.s(out, "code"), D.s(out, "message"));
      return (ObjectNode) out.path("data");
    } catch (Fault e) {
      throw e;
    } catch (Exception e) {
      throw new Fault(503, "DEPENDENCY_UNAVAILABLE", cap + " is unavailable");
    }
  }
}
