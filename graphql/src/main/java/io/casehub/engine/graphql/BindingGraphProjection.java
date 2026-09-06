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
package io.casehub.engine.graphql;

import io.casehub.api.model.Binding;
import io.casehub.api.model.BindingTarget;
import io.casehub.api.model.CapabilityTarget;
import io.casehub.api.model.ExtensionTarget;
import io.casehub.api.model.JudgmentTarget;
import io.casehub.api.model.SignalTarget;
import io.casehub.api.model.SubCaseTarget;
import io.casehub.engine.graphql.dto.BindingEdgeType;
import io.casehub.engine.graphql.dto.BindingGraphType;
import io.casehub.engine.graphql.dto.BindingNodeType;
import io.casehub.engine.graphql.dto.EdgeKind;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class BindingGraphProjection {

  private BindingGraphProjection() {}

  public static BindingGraphType project(List<Binding> bindings) {
    List<BindingNodeType> nodes = new ArrayList<>();
    List<BindingEdgeType> edges = new ArrayList<>();
    List<String> compensationGaps = new ArrayList<>();

    Map<String, String> channelProducers = new HashMap<>();
    Map<String, Set<String>> producedKeysIndex = new HashMap<>();

    for (Binding b : bindings) {
      nodes.add(new BindingNodeType(b.getName(), targetTypeName(b.target()), b.isCompensation()));

      if (b.getCompensateRef() != null) {
        edges.add(
            new BindingEdgeType(b.getName(), b.getCompensateRef(), EdgeKind.COMPENSATION, null));
      } else if (!b.isCompensation()) {
        compensationGaps.add(b.getName());
      }

      if (b.getProduces() != null) {
        channelProducers.put(b.getProduces(), b.getName());
      }

      if (b.getProducedKeys() != null) {
        for (String key : b.getProducedKeys()) {
          producedKeysIndex.computeIfAbsent(key, k -> new LinkedHashSet<>()).add(b.getName());
        }
      }
    }

    for (Binding b : bindings) {
      if (b.getConsumes() != null) {
        String producer = channelProducers.get(b.getConsumes());
        if (producer != null) {
          edges.add(
              new BindingEdgeType(producer, b.getName(), EdgeKind.DATA_FLOW, b.getConsumes()));
        }
      }

      if (b.getRequiredKeys() != null) {
        for (String requiredKey : b.getRequiredKeys()) {
          Set<String> producers = producedKeysIndex.get(requiredKey);
          if (producers != null) {
            for (String producerName : producers) {
              if (!producerName.equals(b.getName())) {
                edges.add(
                    new BindingEdgeType(
                        producerName, b.getName(), EdgeKind.TRIGGER_DEPENDENCY, requiredKey));
              }
            }
          }
        }
      }
    }

    return new BindingGraphType(nodes, edges, compensationGaps);
  }

  static String targetTypeName(BindingTarget target) {
    return switch (target) {
      case CapabilityTarget ignored -> "capability";
      case JudgmentTarget ignored -> "judgment";
      case io.casehub.api.model.HumanTaskTarget ignored -> "judgment";
      case SubCaseTarget ignored -> "sub-case";
      case SignalTarget ignored -> "signal";
      case ExtensionTarget ignored -> "extension";
    };
  }
}
