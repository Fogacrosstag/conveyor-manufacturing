package com.conveyor.platform;

import java.util.UUID;

public final class Context {
  private static final ThreadLocal<String> ID =
      ThreadLocal.withInitial(() -> UUID.randomUUID().toString());

  private Context() {}

  public static String id() {
    return ID.get();
  }

  public static void set(String id) {
    try {
      ID.set(UUID.fromString(id).toString());
    } catch (Exception e) {
      ID.set(UUID.randomUUID().toString());
    }
    org.slf4j.MDC.put("correlationId", ID.get());
  }

  public static void clear() {
    ID.remove();
    org.slf4j.MDC.remove("correlationId");
  }
}
