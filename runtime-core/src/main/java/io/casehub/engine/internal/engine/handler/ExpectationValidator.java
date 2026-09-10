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
package io.casehub.engine.internal.engine.handler;

import com.fasterxml.jackson.databind.JsonNode;
import io.casehub.api.context.ContextLayer;
import io.casehub.api.model.CaseDefinition;
import io.casehub.engine.common.internal.model.CaseInstance;
import io.casehub.engine.common.internal.monitoring.ExpectedEffectResolver;
import io.casehub.engine.plan.goap.Condition;
import io.casehub.engine.plan.goap.GoapWorldState;
import io.casehub.engine.plan.monitoring.ExpectedEffects;
import io.casehub.engine.plan.monitoring.MonitoringConfig;
import io.casehub.engine.plan.monitoring.ViolationRecord;
import java.util.ArrayList;
import java.util.List;

public class ExpectationValidator {

  private final ExpectedEffectResolver effectResolver;

  public ExpectationValidator(ExpectedEffectResolver effectResolver) {
    this.effectResolver = effectResolver;
  }

  public ExpectationValidationResult validate(
      CaseInstance instance, CaseDefinition definition, String bindingName, String compoundId) {

    MonitoringConfig config = definition.getMonitoringConfig();
    if (config == null || !config.enabled()) {
      return null;
    }

    ExpectedEffects expected = effectResolver.resolve(definition, bindingName);
    if (expected.isEmpty()) {
      return null;
    }

    JsonNode workingLayer = instance.getCaseContext().layer(ContextLayer.WORKING).asJsonNode();
    GoapWorldState worldState = GoapWorldState.openWorld(workingLayer);

    List<ViolationRecord> violations = new ArrayList<>();
    for (var entry : expected.effects().entrySet()) {
      Condition expectedCondition = Condition.fromBoolean(entry.getValue());
      Condition actual = worldState.get(entry.getKey());
      if (actual != expectedCondition) {
        violations.add(new ViolationRecord(entry.getKey(), entry.getValue(), actual));
      }
    }

    double ratio = violations.size() / (double) expected.effects().size();

    return new ExpectationValidationResult(bindingName, compoundId, expected, violations, ratio);
  }
}
