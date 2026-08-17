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
package io.casehub.engine.planning.control;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.casehub.api.context.CaseContext;
import io.casehub.api.context.ContextLayer;
import io.casehub.api.context.ReadableLayer;
import io.casehub.api.engine.PlanExecutionContext;
import io.casehub.api.model.Binding;
import io.casehub.api.model.CaseDefinition;
import io.casehub.api.model.CaseStatus;
import io.casehub.api.model.ContextChangeTrigger;
import io.casehub.engine.plan.goap.GoapAction;
import io.casehub.engine.planning.plan.DefaultCasePlanModel;
import io.casehub.worker.api.Capability;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class GoapPlanningStrategyTest {

  private final GoapPlanningStrategy strategy = new GoapPlanningStrategy();

  @Test
  void id_is_goap() {
    assertThat(strategy.id()).isEqualTo("goap");
  }

  @Test
  void selects_first_planned_action() {
    var a1 = new GoapAction("analyse", Map.of(), Map.of("analysisResult", true), 0.3);
    var a2 =
        new GoapAction(
            "assess", Map.of("analysisResult", true), Map.of("riskAssessment", true), 0.5);

    var definition = mock(CaseDefinition.class);
    when(definition.getGoapActions()).thenReturn(List.of(a1, a2));
    when(definition.getGoalToEffectKeys()).thenReturn(Map.of("goal", Set.of("riskAssessment")));

    var workingLayer = mock(ReadableLayer.class);
    when(workingLayer.getKeys()).thenReturn(Set.of());

    var caseContext = mock(CaseContext.class);
    when(caseContext.layer(ContextLayer.WORKING)).thenReturn(workingLayer);

    var cap1 = Capability.builder().name("analyse").inputSchema(".").outputSchema(".").build();
    var cap2 = Capability.builder().name("assess").inputSchema(".").outputSchema(".").build();

    var b1 =
        Binding.builder()
            .name("analyse")
            .capability(cap1)
            .on(new ContextChangeTrigger("true"))
            .build();
    var b2 =
        Binding.builder()
            .name("assess")
            .capability(cap2)
            .on(new ContextChangeTrigger("true"))
            .build();

    var pec =
        new PlanExecutionContext(
            UUID.randomUUID(),
            definition,
            caseContext,
            CaseStatus.RUNNING,
            "default",
            List.of(),
            null,
            null);

    var plan = new DefaultCasePlanModel(UUID.randomUUID());
    List<Binding> result = strategy.select(plan, pec, List.of(b1, b2));
    assertThat(result).hasSize(1);
    assertThat(result.get(0).getName()).isEqualTo("analyse");
  }

  @Test
  void returns_empty_when_no_eligible() {
    var definition = mock(CaseDefinition.class);
    when(definition.getGoapActions()).thenReturn(List.of());
    when(definition.getGoalToEffectKeys()).thenReturn(Map.of());

    var pec =
        new PlanExecutionContext(
            UUID.randomUUID(),
            definition,
            mock(CaseContext.class),
            CaseStatus.RUNNING,
            "default",
            List.of(),
            null,
            null);

    var plan = new DefaultCasePlanModel(UUID.randomUUID());
    List<Binding> result = strategy.select(plan, pec, List.of());
    assertThat(result).isEmpty();
  }

  @Test
  void skips_actions_not_in_eligible_bindings() {
    var a1 = new GoapAction("analyse", Map.of(), Map.of("analysisResult", true), 0.3);
    var a2 =
        new GoapAction(
            "assess", Map.of("analysisResult", true), Map.of("riskAssessment", true), 0.5);

    var definition = mock(CaseDefinition.class);
    when(definition.getGoapActions()).thenReturn(List.of(a1, a2));
    when(definition.getGoalToEffectKeys()).thenReturn(Map.of("goal", Set.of("riskAssessment")));

    var workingLayer = mock(ReadableLayer.class);
    when(workingLayer.getKeys()).thenReturn(Set.of("analysisResult"));

    var caseContext = mock(CaseContext.class);
    when(caseContext.layer(ContextLayer.WORKING)).thenReturn(workingLayer);

    var cap2 = Capability.builder().name("assess").inputSchema(".").outputSchema(".").build();
    var b2 =
        Binding.builder()
            .name("assess")
            .capability(cap2)
            .on(new ContextChangeTrigger("true"))
            .build();

    var pec =
        new PlanExecutionContext(
            UUID.randomUUID(),
            definition,
            caseContext,
            CaseStatus.RUNNING,
            "default",
            List.of(),
            null,
            null);

    var plan = new DefaultCasePlanModel(UUID.randomUUID());
    List<Binding> result = strategy.select(plan, pec, List.of(b2));
    assertThat(result).hasSize(1);
    assertThat(result.get(0).getName()).isEqualTo("assess");
  }
}
