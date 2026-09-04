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
import io.casehub.engine.graphql.dto.CompensationEdgeType;
import io.casehub.engine.graphql.dto.CompensationGraphType;
import io.casehub.engine.graphql.dto.CompensationNodeType;
import java.util.ArrayList;
import java.util.List;

public final class CompensationGraphProjection {

  private CompensationGraphProjection() {}

  public static CompensationGraphType project(List<Binding> bindings) {
    List<CompensationNodeType> nodes = new ArrayList<>();
    List<CompensationEdgeType> edges = new ArrayList<>();
    List<String> gaps = new ArrayList<>();

    for (Binding b : bindings) {
      nodes.add(
          new CompensationNodeType(b.getName(), targetTypeName(b.target()), b.isCompensation()));

      if (b.getCompensateRef() != null) {
        edges.add(new CompensationEdgeType(b.getName(), b.getCompensateRef()));
      } else if (!b.isCompensation()) {
        gaps.add(b.getName());
      }
    }
    return new CompensationGraphType(nodes, edges, gaps);
  }

  static String targetTypeName(BindingTarget target) {
    return switch (target) {
      case CapabilityTarget ignored -> "capability";
      case JudgmentTarget ignored -> "judgment";
      case SubCaseTarget ignored -> "sub-case";
      case SignalTarget ignored -> "signal";
      case ExtensionTarget ignored -> "extension";
    };
  }
}
