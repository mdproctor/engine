/*
 * Copyright 2026-Present The Case Hub Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.casehub.engine.common.internal.context;

import com.fasterxml.jackson.databind.JsonNode;
import io.casehub.api.context.CaseContext;
import io.casehub.api.context.ContextBridge;
import io.casehub.api.context.JacksonPojoBridge;
import io.casehub.api.context.MapBridge;
import io.casehub.api.model.CaseDefinition;
import io.casehub.worker.api.Worker;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import java.util.Map;

@ApplicationScoped
public class BridgeResolver {

  private static final MapBridge MAP_BRIDGE = new MapBridge();
  private final Instance<ContextBridge<?>> bridges;

  @Inject
  public BridgeResolver(Instance<ContextBridge<?>> bridges) {
    this.bridges = bridges;
  }

  public ContextBridge<?> resolve(Worker worker, CaseDefinition definition) {
    Class<?> inputType = worker.function().inputType();

    if (definition != null && definition.getDefaultWorkerBridge() != null) {
      ContextBridge<?> def = definition.getDefaultWorkerBridge();
      if (def.contextType().equals(inputType)) {
        return def;
      }
    }

    for (ContextBridge<?> bridge : bridges) {
      if (bridge.contextType().equals(inputType)) {
        return bridge;
      }
    }

    if (Map.class.equals(inputType)) {
      return MAP_BRIDGE;
    }

    return new JacksonPojoBridge<>(inputType);
  }

  public ContextBridge<?> resolveByType(Class<?> payloadType) {
    for (ContextBridge<?> bridge : bridges) {
      if (bridge.contextType().equals(payloadType)) {
        return bridge;
      }
    }
    if (Map.class.equals(payloadType)) {
      return MAP_BRIDGE;
    }
    return new JacksonPojoBridge<>(payloadType);
  }

  public ContextBridge<?> resolveByTypeName(String typeName) {
    if (typeName == null) {
      return MAP_BRIDGE;
    }
    try {
      return resolveByType(Class.forName(typeName));
    } catch (ClassNotFoundException e) {
      return MAP_BRIDGE;
    }
  }

  public ContextBridge<?> resolveByTypeNameStrict(String typeName) {
    if (typeName == null) {
      throw new IllegalArgumentException("typeName must not be null for strict resolution");
    }
    try {
      return resolveByType(Class.forName(typeName));
    } catch (ClassNotFoundException e) {
      throw new IllegalArgumentException("Bridge type class not found: " + typeName, e);
    }
  }

  @SuppressWarnings("unchecked")
  public <T> T initialise(ContextBridge<T> bridge, CaseContext context, JsonNode narrowedInput) {
    return bridge.initialise(context, narrowedInput);
  }

  @SuppressWarnings("unchecked")
  public <T> JsonNode serialise(ContextBridge<T> bridge, Object input) {
    return bridge.serialise((T) input);
  }

  @SuppressWarnings("unchecked")
  public <T> T deserialise(ContextBridge<T> bridge, JsonNode payload) {
    return bridge.deserialise(payload);
  }

  @SuppressWarnings("unchecked")
  public <T> Map<String, Object> extractOutput(ContextBridge<T> bridge, Object context) {
    return bridge.extractOutput((T) context);
  }
}
