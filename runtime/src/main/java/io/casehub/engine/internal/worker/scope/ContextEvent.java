package io.casehub.engine.internal.worker.scope;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Map;

public record ContextEvent(JsonNode contextSnapshot, Map<String, Object> changeMetadata) {

  public static final ContextEvent SHUTDOWN = new ContextEvent(null, null);

  public boolean isShutdown() {
    return this == SHUTDOWN;
  }
}
