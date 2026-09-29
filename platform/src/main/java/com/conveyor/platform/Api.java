package com.conveyor.platform;

import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
public class Api {
  private final List<Capability> caps;
  private final Store store;
  private final Gateway gateway;
  private final String service;
  private final String adminToken;

  public Api(
      List<Capability> caps,
      Store store,
      Gateway gateway,
      @Value("${spring.application.name}") String service,
      @Value("${app.admin-token}") String token,
      @Value("${app.baseline:false}") boolean baseline,
      @Value("${app.initial-owner:LOCAL}") String initialOwner) {
    this.caps = caps;
    this.store = store;
    this.gateway = gateway;
    this.service = service;
    this.adminToken = token;
    store.atomic(
        () -> {
          for (Capability c : caps) {
            if (store.find("_owner", c.name()).isEmpty())
              store.owner(
                  c.name(),
                  service.equals("legacy-monolith") && !baseline && !c.name().equals("products")
                      ? "REMOTE"
                      : initialOwner);
          }
          return null;
        });
  }

  @RequestMapping(
      value = {
        "/engineering/**",
        "/materials/**",
        "/planning/**",
        "/production-orders/**",
        "/quality/**",
        "/products/**",
        "/shop-floor/**",
        "/audit/**",
        "/api/legacy/**"
      },
      method = {RequestMethod.GET, RequestMethod.POST, RequestMethod.PUT})
  public ResponseEntity<Object> handle(
      HttpServletRequest request, @RequestBody(required = false) ObjectNode input) {
    String path = request.getRequestURI();
    boolean legacy = path.startsWith("/api/legacy");
    if (legacy) path = path.substring(11);
    final String route = path;
    ObjectNode body = input == null ? D.obj() : input;
    String method = request.getMethod();
    String key = Optional.ofNullable(request.getHeader("Idempotency-Key")).orElse("");
    try {
      Capability c =
          caps.stream()
              .filter(x -> route.equals(x.prefix()) || route.startsWith(x.prefix() + "/"))
              .findFirst()
              .orElseThrow(() -> new Fault(404, "NOT_FOUND", "Unknown capability"));
      Object result;
      if (store.owner(c.name()).equals("REMOTE")) {
        result = gateway.call(c.name(), method, route, body, key);
      } else if (method.equals("GET")) {
        result = c.execute(method, route, body);
      } else {
        D.check(
            !key.isBlank() && key.length() <= 128,
            "Idempotency-Key header is required (max 128 characters)");
        result =
            store.atomic(
                () -> {
                  D.state(store.owner(c.name()).equals("LOCAL"), "Capability is frozen");
                  String kind = "_request:" + c.name();
                  ObjectNode fingerprint = D.obj().put("method", method).put("path", route);
                  fingerprint.set("body", body);
                  Optional<ObjectNode> previous = store.find(kind, key);
                  if (previous.isPresent()) {
                    D.state(
                        previous.get().path("request").equals(fingerprint),
                        "Idempotency key reused for a different request");
                    return previous.get().get("response");
                  }
                  Object answer = c.execute(method, route, body);
                  ObjectNode record = D.obj();
                  record.set("request", fingerprint);
                  record.set("response", D.JSON.valueToTree(answer));
                  store.put(kind, key, record);
                  return answer;
                });
      }
      ObjectNode response = D.obj().put("timestamp", Instant.now().toString());
      response.set("data", D.JSON.valueToTree(result));
      return ResponseEntity.ok().header("X-Correlation-ID", Context.id()).body(response);
    } finally {
      /* CorrelationFilter owns request context cleanup. */
    }
  }

  @RequestMapping(
      value = "/internal/migration/{cap}/{action}",
      method = {RequestMethod.GET, RequestMethod.POST})
  public Object migration(
      @PathVariable String cap,
      @PathVariable String action,
      @RequestHeader(value = "X-Admin-Token", defaultValue = "") String token,
      @RequestBody(required = false) ObjectNode body) {
    if (!adminToken.equals(token) || token.length() < 16)
      throw new Fault(403, "FORBIDDEN", "Invalid administration token");
    Capability c =
        caps.stream()
            .filter(x -> x.name().equals(cap))
            .findFirst()
            .orElseThrow(() -> new Fault(404, "NOT_FOUND", "Unknown capability"));
    return store.atomic(
        () -> {
          switch (action) {
            case "state":
              return D.obj()
                  .put("state", store.owner(cap))
                  .put(
                      "pendingOutbox",
                      store.db.queryForObject(
                          "select count(*) from outbox_events where published_at is null",
                          Long.class));
            case "freeze":
              store.owner(cap, "FROZEN");
              return D.obj().put("state", "FROZEN");
            case "snapshot":
              {
                D.state(store.owner(cap).equals("FROZEN"), "Freeze before snapshot");
                ObjectNode snapshot = D.obj();
                Set<String> kinds = new HashSet<>(c.kinds());
                kinds.add("_request:" + cap);
                for (String kind : kinds)
                  snapshot.set(
                      kind,
                      D.JSON.valueToTree(
                          store.db.query(
                              "select id,body::text from records where kind=? order by id",
                              (rs, i) ->
                                  D.obj()
                                      .put("id", rs.getString(1))
                                      .set("body", D.parse(rs.getString(2))),
                              kind)));
                return snapshot;
              }
            case "import":
              {
                D.state(store.owner(cap).equals("FROZEN"), "Freeze target before import");
                D.check(body != null, "Snapshot required");
                Set<String> kinds = new HashSet<>(c.kinds());
                kinds.add("_request:" + cap);
                D.check(body.size() == kinds.size(), "Incomplete snapshot");
                for (String kind : kinds) {
                  D.check(body.path(kind).isArray(), "Missing kind " + kind);
                  store.db.update("delete from records where kind=?", kind);
                  for (var row : body.path(kind)) {
                    store.put(kind, row.path("id").asText(), (ObjectNode) row.path("body"));
                  }
                }
                return D.obj().put("imported", true);
              }
            case "activate":
              store.owner(cap, "LOCAL");
              return D.obj().put("state", "LOCAL");
            case "redirect":
              store.owner(cap, "REMOTE");
              return D.obj().put("state", "REMOTE");
            default:
              throw new Fault(404, "NOT_FOUND", "Unknown migration action");
          }
        });
  }

  @ExceptionHandler(Fault.class)
  public ResponseEntity<Object> fault(Fault e) {
    return ResponseEntity.status(e.status)
        .body(
            D.obj()
                .put("code", e.code)
                .put("message", e.getMessage())
                .put("timestamp", Instant.now().toString())
                .put("traceId", Context.id()));
  }

  @ExceptionHandler(org.springframework.http.converter.HttpMessageNotReadableException.class)
  public ResponseEntity<Object> invalidJson(Exception e) {
    return ResponseEntity.badRequest()
        .body(
            D.obj()
                .put("code", "INVALID_JSON")
                .put("message", "Expected a valid JSON object")
                .put("traceId", Context.id()));
  }

  @ExceptionHandler(Exception.class)
  public ResponseEntity<Object> unexpected(Exception e) {
    org.slf4j.LoggerFactory.getLogger(Api.class).error("Request failed", e);
    return ResponseEntity.status(500)
        .body(
            D.obj()
                .put("code", "INTERNAL_ERROR")
                .put("message", "Request failed; see service logs")
                .put("traceId", Context.id()));
  }
}
