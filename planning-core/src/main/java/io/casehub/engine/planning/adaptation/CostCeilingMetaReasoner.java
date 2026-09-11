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
package io.casehub.engine.planning.adaptation;

import io.casehub.api.model.CaseDefinition;
import io.casehub.api.model.FailureCategory;
import io.casehub.engine.plan.adaptation.AdaptationDecision;
import io.casehub.engine.plan.adaptation.AdaptationMetaReasoner;
import io.casehub.engine.plan.adaptation.MetaReasoningContext;
import io.casehub.engine.plan.adaptation.RefineScope;

public class CostCeilingMetaReasoner implements AdaptationMetaReasoner {

  static final int DEFAULT_MAX_ADAPTATIONS = 5;

  @Override
  public AdaptationDecision evaluate(MetaReasoningContext context) {
    CaseDefinition def = context.adaptationContext().definition();
    String compoundId = context.adaptationContext().compoundId();

    int maxAdaptations =
        def.getMaxAdaptations() != null ? def.getMaxAdaptations() : DEFAULT_MAX_ADAPTATIONS;

    if (context.adaptationCount() >= maxAdaptations) {
      return new AdaptationDecision.Concede(
          "Adaptation ceiling reached (" + context.adaptationCount() + "/" + maxAdaptations + ")",
          compoundId);
    }

    if (context.latestFailureCategory() instanceof FailureCategory.Infeasible inf) {
      return new AdaptationDecision.Concede("Infeasible failure: " + inf.reason(), compoundId);
    }

    if (context.latestFailureCategory() instanceof FailureCategory.Transient) {
      return new AdaptationDecision.Persist(
          "Transient failure — retry/reroute preferred over adaptation");
    }

    if (context.latestFailureCategory() instanceof FailureCategory.Knowledge) {
      RefineScope scope = context.adaptationCount() > 0 ? RefineScope.COMPOUND : RefineScope.LOCAL;
      return new AdaptationDecision.Refine(
          scope,
          "Knowledge failure — "
              + (scope == RefineScope.COMPOUND
                  ? "repeated, compound re-plan"
                  : "first occurrence, local repair"));
    }

    return new AdaptationDecision.Refine(
        RefineScope.COMPOUND, "Divergence-gated adaptation after successful completion");
  }

  @Override
  public String id() {
    return "cost-ceiling";
  }
}
