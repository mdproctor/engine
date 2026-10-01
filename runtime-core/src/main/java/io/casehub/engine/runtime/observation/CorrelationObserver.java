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
package io.casehub.engine.internal.observation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.TextNode;
import io.casehub.api.spi.observation.EnvironmentObserver;
import io.casehub.api.spi.observation.Observation;
import io.casehub.api.spi.observation.ObservationContext;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.thisptr.jackson.jq.BuiltinFunctionLoader;
import net.thisptr.jackson.jq.Scope;
import net.thisptr.jackson.jq.Versions;

public final class CorrelationObserver implements EnvironmentObserver {

  private static final Scope ROOT_SCOPE;

  static {
    ROOT_SCOPE = Scope.newEmptyScope();
    BuiltinFunctionLoader.getInstance().loadFunctions(Versions.JQ_1_6, ROOT_SCOPE);
  }

  private final Set<String> keys;
  private final String jqCondition;
  private final net.thisptr.jackson.jq.JsonQuery compiledQuery;

  private CorrelationObserver(Set<String> keys, String jqCondition) {
    this.keys = Set.copyOf(keys);
    this.jqCondition = jqCondition;
    try {
      this.compiledQuery = net.thisptr.jackson.jq.JsonQuery.compile(jqCondition, Versions.JQ_1_6);
    } catch (net.thisptr.jackson.jq.exception.JsonQueryException e) {
      throw new IllegalArgumentException("Invalid JQ condition: " + jqCondition, e);
    }
  }

  public static CorrelationObserver of(Set<String> keys, String jqCondition) {
    return new CorrelationObserver(keys, jqCondition);
  }

  @Override
  public String observerType() {
    return "correlation";
  }

  @Override
  public Set<String> watchedKeys() {
    return keys;
  }

  @Override
  public List<Observation> observe(ObservationContext ctx) {
    try {
      Scope childScope = Scope.newChildScope(ROOT_SCOPE);
      java.util.List<JsonNode> results = new java.util.ArrayList<>();
      compiledQuery.apply(childScope, ctx.snapshot(), results::add);
      if (results.isEmpty()) {
        return List.of();
      }
      JsonNode result = results.get(0);
      if (!result.isBoolean() || !result.asBoolean()) {
        return List.of();
      }
      Map<String, JsonNode> details = new LinkedHashMap<>();
      details.put("condition", TextNode.valueOf(jqCondition));
      return List.of(new Observation("multi-key-correlation", 1.0, details, Instant.now()));
    } catch (Exception e) {
      return List.of();
    }
  }
}
