package com.conveyor.platform;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Set;

public interface Capability {
  String name();

  String prefix();

  Set<String> kinds();

  Object execute(String method, String path, ObjectNode body);

  default void on(Event e) {}
}
