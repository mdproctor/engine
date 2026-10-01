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
package io.casehub.engine.internal.improvement;

import io.casehub.api.model.stigmergy.EscalationContext;
import io.casehub.api.model.stigmergy.EscalationPolicy;
import io.casehub.api.model.stigmergy.EscalationResult;
import io.casehub.api.model.stigmergy.EscalationTrigger;
import io.casehub.api.model.stigmergy.EscalationTrigger.EscalationLayer;
import io.casehub.api.model.stigmergy.WatchPattern;
import io.casehub.api.spi.improvement.EscalationProvider;
import io.quarkus.arc.DefaultBean;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@DefaultBean
@ApplicationScoped
public class DefaultEscalationProvider implements EscalationProvider {

  @Override
  public EscalationResult evaluate(
      UUID caseId,
      String tenancyId,
      String stage,
      EscalationContext context,
      EscalationPolicy policy,
      List<WatchPattern> activeWatchPatterns) {
    var triggers = new ArrayList<EscalationTrigger>();

    evaluateCategoryRules(context, policy, triggers);
    evaluateWatchPatterns(context, activeWatchPatterns, triggers);

    double confidence = computeConfidence(context);

    if (confidence < policy.effectiveConfidenceThreshold()) {
      triggers.add(
          new EscalationTrigger(
              EscalationLayer.CONFIDENCE_SCORE,
              "Confidence "
                  + confidence
                  + " below threshold "
                  + policy.effectiveConfidenceThreshold()));
    }

    return new EscalationResult(!triggers.isEmpty(), confidence, List.copyOf(triggers));
  }

  private void evaluateCategoryRules(
      EscalationContext context, EscalationPolicy policy, List<EscalationTrigger> triggers) {
    if (policy.categoryRules() == null || context.category() == null) {
      return;
    }
    if (policy.categoryRules().neverEscalate().contains(context.category())) {
      return;
    }
    if (policy.categoryRules().alwaysEscalate().contains(context.category())) {
      triggers.add(
          new EscalationTrigger(
              EscalationLayer.CATEGORY_RULE,
              "Category '" + context.category() + "' always escalates"));
    }
  }

  private void evaluateWatchPatterns(
      EscalationContext context,
      List<WatchPattern> activeWatchPatterns,
      List<EscalationTrigger> triggers) {
    for (var pattern : activeWatchPatterns) {
      if (matches(pattern, context)) {
        triggers.add(
            new EscalationTrigger(
                EscalationLayer.WATCH_PATTERN, "Matches watch pattern '" + pattern.id() + "'"));
      }
    }
  }

  private boolean matches(WatchPattern pattern, EscalationContext context) {
    if (pattern.category() != null && pattern.category().equals(context.category())) {
      return true;
    }
    if (pattern.areaId() != null && pattern.areaId().equals(context.areaId())) {
      return true;
    }
    if (pattern.targetPattern() != null
        && context.target() != null
        && context.target().contains(pattern.targetPattern())) {
      return true;
    }
    if (pattern.minEstimatedSize() != null
        && context.estimatedSize() != null
        && context.estimatedSize() >= pattern.minEstimatedSize()) {
      return true;
    }
    return false;
  }

  private double computeConfidence(EscalationContext context) {
    double confidence = 1.0;
    if (context.estimatedSize() != null && context.estimatedSize() > 200) {
      confidence -= 0.2;
    }
    return Math.max(0.0, Math.min(1.0, confidence));
  }
}
