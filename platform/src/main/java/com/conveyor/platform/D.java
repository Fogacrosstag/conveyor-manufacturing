package com.conveyor.platform;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import java.math.BigDecimal;
import java.util.*;

/** JSON field access and validation shared by the capability handlers. */
public final class D {
  public static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules();

  private D() {}

  public static ObjectNode obj() {
    return JSON.createObjectNode();
  }

  public static ObjectNode parse(String s) {
    try {
      return (ObjectNode) JSON.readTree(s);
    } catch (Exception e) {
      throw new Fault(400, "INVALID_JSON", "Expected a JSON object");
    }
  }

  public static ObjectNode of(Object x) {
    return JSON.valueToTree(x);
  }

  public static String s(JsonNode n, String k) {
    return n.path(k).asText("");
  }

  public static String required(JsonNode n, String k) {
    String s = s(n, k);
    check(!s.isBlank(), k + " is required");
    return s;
  }

  public static BigDecimal num(JsonNode n, String k) {
    check(n.path(k).isNumber(), k + " must be numeric");
    return n.get(k).decimalValue();
  }

  public static BigDecimal positive(JsonNode n, String k) {
    BigDecimal d = num(n, k);
    check(d.signum() > 0, k + " must be positive");
    return d;
  }

  public static int integer(JsonNode n, String k) {
    try {
      return positive(n, k).intValueExact();
    } catch (ArithmeticException e) {
      throw new Fault(422, "VALIDATION", k + " must be a positive integer");
    }
  }

  public static String id(JsonNode n) {
    String id = s(n, "id");
    if (id.isBlank()) return UUID.randomUUID().toString();
    try {
      return UUID.fromString(id).toString();
    } catch (Exception e) {
      throw new Fault(422, "VALIDATION", "id must be a UUID");
    }
  }

  public static ArrayNode array(JsonNode n, String k) {
    check(n.path(k).isArray(), k + " must be an array");
    return (ArrayNode) n.get(k);
  }

  public static void check(boolean b, String msg) {
    if (!b) throw new Fault(422, "VALIDATION", msg);
  }

  public static void state(boolean b, String msg) {
    if (!b) throw new Fault(409, "INVALID_STATE", msg);
  }

  public static ObjectNode status(ObjectNode d, String s) {
    return d.put("status", s);
  }

  public static boolean is(JsonNode d, String s) {
    return s.equals(s(d, "status"));
  }

  public static void only(ObjectNode n, String... fields) {
    Set<String> a = Set.of(fields);
    n.fieldNames().forEachRemaining(k -> check(a.contains(k), "Unknown field: " + k));
  }
}
