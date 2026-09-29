package com.conveyor.boot;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.conveyor.platform.*;
import com.fasterxml.jackson.databind.node.*;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;

@Testcontainers
@SpringBootTest(
    properties = {
      "app.baseline=true",
      "app.messaging-enabled=false",
      "management.tracing.enabled=false"
    })
class LegacyWorkflowTest {
  @Container
  static PostgreSQLContainer<?> database = new PostgreSQLContainer<>("postgres:16.6-alpine");

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry r) {
    r.add("spring.datasource.url", database::getJdbcUrl);
    r.add("spring.datasource.username", database::getUsername);
    r.add("spring.datasource.password", database::getPassword);
  }

  @Autowired Store store;
  @Autowired List<Capability> capabilities;

  @BeforeEach
  void clear() {
    store.db.execute("truncate records,outbox_events,processed_events restart identity");
  }

  ObjectNode command(String cap, String path, ObjectNode body) {
    return store.atomic(
        () ->
            D.of(
                capabilities.stream()
                    .filter(c -> c.name().equals(cap))
                    .findFirst()
                    .orElseThrow()
                    .execute("POST", path, body)));
  }

  ObjectNode get(String cap, String path) {
    return D.of(
        capabilities.stream()
            .filter(c -> c.name().equals(cap))
            .findFirst()
            .orElseThrow()
            .execute("GET", path, D.obj()));
  }

  String material, product, bom;

  ObjectNode prepare(int stock) {
    ObjectNode m =
        command(
            "material",
            "/materials",
            D.obj()
                .put("materialCode", "MOTOR")
                .put("name", "Motor")
                .put("unitOfMeasure", "EA")
                .put("minimumStockLevel", 1));
    material = D.s(m, "id");
    command(
        "material",
        "/materials/receive",
        D.obj().put("materialId", material).put("quantity", stock));
    product =
        D.s(
            command(
                "products",
                "/products",
                D.obj()
                    .put("code", "BC-200")
                    .put("name", "Belt Conveyor")
                    .put("productType", "BELT_CONVEYOR")),
            "id");
    ObjectNode spec = D.obj().put("productId", product).put("revision", 1);
    ArrayNode checks = spec.putArray("checks");
    for (String type :
        List.of(
            "DIMENSIONAL_INSPECTION",
            "WELDING_INSPECTION",
            "ELECTRICAL_INSPECTION",
            "FUNCTIONAL_TEST",
            "FINAL_INSPECTION"))
      checks.add(D.obj().put("type", type).put("minimum", 9).put("maximum", 11));
    String specId = D.s(command("engineering", "/engineering/specifications", spec), "id");
    command("engineering", "/engineering/specifications/" + specId + "/approve", D.obj());
    ObjectNode b =
        D.obj().put("productId", product).put("revision", 1).put("specificationId", specId);
    b.putArray("items")
        .add(D.obj().put("materialId", material).put("quantity", 1).put("unitOfMeasure", "EA"));
    bom = D.s(command("engineering", "/engineering/boms", b), "id");
    command("engineering", "/engineering/boms/" + bom + "/approve", D.obj());
    int seq = 0;
    for (String code :
        List.of(
            "CUTTING",
            "WELDING",
            "MACHINING",
            "ASSEMBLY",
            "ELECTRICAL_INSTALLATION",
            "TESTING",
            "PACKAGING"))
      command(
          "production-planning",
          "/planning/work-centers",
          D.obj()
              .put("code", code)
              .put("name", code)
              .put("sequence", ++seq)
              .put("durationMinutes", 10)
              .put("capacity", 1)
              .put("recoveryAllowanceMinutes", 30)
              .put("equipmentId", UUID.randomUUID().toString()));
    return order(5);
  }

  ObjectNode order(int quantity) {
    return command(
        "production-order",
        "/production-orders",
        D.obj()
            .put("productId", product)
            .put("bomRevisionId", bom)
            .put("quantity", quantity)
            .put("priority", "HIGH"));
  }

  @Test
  void manufacturingFailureQualityReworkAndCompletion() {
    ObjectNode order = prepare(10);
    String oid = D.s(order, "id"), path = "/production-orders/" + oid;
    assertThrows(Fault.class, () -> command("production-order", path + "/complete", D.obj()));
    command("production-order", path + "/submit", D.obj());
    assertEquals("MATERIALS_RESERVED", D.s(get("production-order", path), "status"));
    command("production-planning", "/planning/dispatch", D.obj());
    assertEquals("SCHEDULED", D.s(get("production-order", path), "status"));
    var ops =
        store.all("operation").stream()
            .sorted(Comparator.comparingInt(x -> x.path("sequence").asInt()))
            .toList();
    String first = "/shop-floor/operations/" + D.s(ops.get(0), "id");
    assertThrows(Fault.class, () -> command("shop-floor", first + "/start", D.obj()));
    command("production-order", path + "/start", D.obj());
    assertThrows(
        Fault.class,
        () ->
            command(
                "shop-floor",
                "/shop-floor/operations/" + D.s(ops.get(1), "id") + "/start",
                D.obj()));
    command("shop-floor", first + "/start", D.obj());
    ObjectNode event =
        D.obj()
            .put("eventId", UUID.randomUUID().toString())
            .put("equipmentId", D.s(ops.get(0), "equipmentId"))
            .put("workCenterId", D.s(ops.get(0), "workCenterId"))
            .put("productionOrderId", oid)
            .put("eventType", "EQUIPMENT_FAILURE")
            .put("sequence", 1);
    command("shop-floor", "/shop-floor/events", event);
    assertEquals("PAUSED", D.s(get("shop-floor", first), "status"));
    assertThrows(Fault.class, () -> command("shop-floor", first + "/resume", D.obj()));
    assertTrue(
        get("production-planning", "/planning/schedules/" + oid).path("delayed").asBoolean());
    command(
        "shop-floor",
        "/shop-floor/events",
        event
            .deepCopy()
            .put("eventId", UUID.randomUUID().toString())
            .put("eventType", "EQUIPMENT_RECOVERED")
            .put("sequence", 2));
    command("shop-floor", first + "/resume", D.obj());
    for (ObjectNode op : ops) {
      String opPath = "/shop-floor/operations/" + D.s(op, "id");
      if (op.path("sequence").asInt() > 1) command("shop-floor", opPath + "/start", D.obj());
      ObjectNode result =
          command(
              "shop-floor",
              opPath + "/complete",
              D.obj().put("processedQuantity", 5).put("rejectedQuantity", 0));
      assertEquals(
          result,
          command(
              "shop-floor",
              opPath + "/complete",
              D.obj().put("processedQuantity", 5).put("rejectedQuantity", 0)));
    }
    var inspections = store.all("inspection");
    assertEquals(5, inspections.size());
    String ip = "/quality/inspections/" + D.s(inspections.get(0), "id");
    command("quality-control", ip + "/start", D.obj());
    command(
        "quality-control",
        ip + "/checks",
        D.obj().put("measuredValue", 99).put("inspectorId", "QA"));
    command("quality-control", ip + "/complete", D.obj());
    assertThrows(Fault.class, () -> command("production-order", path + "/complete", D.obj()));
    command("quality-control", ip + "/retry", D.obj().put("reworkNote", "Reworked assembly"));
    for (ObjectNode inspection : inspections) {
      ip = "/quality/inspections/" + D.s(inspection, "id");
      command("quality-control", ip + "/start", D.obj());
      command(
          "quality-control",
          ip + "/checks",
          D.obj().put("measuredValue", 10).put("inspectorId", "QA"));
      command("quality-control", ip + "/complete", D.obj());
    }
    assertEquals("COMPLETED", D.s(get("production-order", path), "status"));
    assertEquals(5, get("material", "/materials/" + material).path("quantityOnHand").asInt());
    assertEquals(0, get("material", "/materials/" + material).path("reservedQuantity").asInt());
    assertEquals(6, store.all("quality-attempt").size());
    assertFalse(store.all("audit-event").isEmpty());
  }

  @Test
  void reservationRaceAndCancellation() throws Exception {
    ObjectNode first = prepare(5);
    ObjectNode second = order(5);
    try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
      List<Future<ObjectNode>> futures = new ArrayList<>();
      for (ObjectNode o : List.of(first, second))
        futures.add(
            pool.submit(
                () ->
                    command(
                        "production-order",
                        "/production-orders/" + D.s(o, "id") + "/submit",
                        D.obj())));
      for (Future<ObjectNode> future : futures) future.get(30, TimeUnit.SECONDS);
    }
    assertEquals(1, store.all("reservation").size());
    assertEquals(0, get("material", "/materials/" + material).path("availableQuantity").asInt());
    String winner = D.s(store.all("reservation").get(0), "id");
    command("production-order", "/production-orders/" + winner + "/cancel", D.obj());
    assertEquals(5, get("material", "/materials/" + material).path("availableQuantity").asInt());
  }

  @Test
  void inboxAndStateRollbackTogether() {
    String id = UUID.randomUUID().toString();
    assertThrows(
        IllegalStateException.class,
        () ->
            store.atomic(
                () -> {
                  assertTrue(store.claim(id, "test"));
                  store.put("test", id, D.obj().put("value", 1));
                  throw new IllegalStateException("simulated failure");
                }));
    assertTrue(store.find("test", id).isEmpty());
    store.atomic(
        () -> {
          assertTrue(store.claim(id, "test"));
          assertFalse(store.claim(id, "test"));
          return null;
        });
  }

  @SuppressWarnings("unchecked")
  @Test
  void failedPublicationRemainsDurable() {
    prepare(10);
    KafkaTemplate<String, String> kafka = mock(KafkaTemplate.class);
    when(kafka.send(anyString(), anyString(), anyString()))
        .thenReturn(CompletableFuture.failedFuture(new IllegalStateException("broker offline")));
    Messaging worker = new Messaging(store, kafka, List.of(), "test", new SimpleMeterRegistry());
    worker.publish();
    assertTrue(
        store.db.queryForObject(
                "select count(*) from outbox_events where published_at is null", Long.class)
            > 0);
    assertTrue(
        store.db.queryForObject("select sum(retry_count) from outbox_events", Long.class) > 0);
    when(kafka.send(anyString(), anyString(), anyString()))
        .thenReturn(CompletableFuture.completedFuture(null));
    worker.publish();
    assertEquals(
        0L,
        store.db.queryForObject(
            "select count(*) from outbox_events where published_at is null", Long.class));
  }
}
