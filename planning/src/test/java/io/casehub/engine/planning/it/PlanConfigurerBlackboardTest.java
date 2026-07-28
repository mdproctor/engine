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
package io.casehub.engine.planning.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import io.casehub.api.engine.CaseHub;
import io.casehub.api.engine.PlanExecutionContext;
import io.casehub.api.model.Binding;
import io.casehub.api.model.CaseDefinition;
import io.casehub.api.model.ContextChangeTrigger;
import io.casehub.api.model.TaskStatus;
import io.casehub.engine.planning.control.BlackboardPlanConfigurer;
import io.casehub.engine.planning.plan.CasePlanModel;
import io.casehub.engine.planning.plan.PlanItemDefinition;
import io.casehub.engine.planning.registry.BlackboardRegistry;
import io.casehub.worker.api.Capability;
import io.casehub.worker.api.Worker;
import io.casehub.worker.api.WorkerFunction;
import io.casehub.worker.api.WorkerResult;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

@QuarkusTest
class PlanConfigurerBlackboardTest {

  static final AtomicInteger callCount = new AtomicInteger(0);

  @Inject BlackboardRegistry registry;
  @Inject ConfiguredCaseBean configuredCase;

  @Test
  void configurer_is_called_and_compound_appears_in_plan_model() {
    callCount.set(0);
    UUID caseId = configuredCase.startCase(Map.of("ready", true));

    await()
        .atMost(10, TimeUnit.SECONDS)
        .untilAsserted(() -> assertThat(registry.get(caseId)).isPresent());

    CasePlanModel plan = registry.get(caseId).get();
    assertThat(plan.getAllCompounds())
        .as("configurer must have added 'configurer-compound' to the plan model")
        .anyMatch(c -> c.name().equals("configurer-compound"));
  }

  @Test
  void configurer_is_called_exactly_once_per_case_instance() {
    callCount.set(0);
    UUID caseId = configuredCase.startCase(Map.of("ready", true));

    await()
        .atMost(10, TimeUnit.SECONDS)
        .untilAsserted(() -> assertThat(registry.get(caseId)).isPresent());

    configuredCase.signal(caseId, "probe", "tick");
    configuredCase.signal(caseId, "probe", "tick-2");

    await()
        .atMost(10, TimeUnit.SECONDS)
        .untilAsserted(
            () ->
                assertThat(callCount.get())
                    .as("configure() must have been called at least once")
                    .isGreaterThanOrEqualTo(1));

    assertThat(callCount.get()).as("configure() must be called exactly once per case").isEqualTo(1);
  }

  @Test
  void compound_added_by_configurer_activates_on_evaluation_cycle() {
    callCount.set(0);
    UUID caseId = configuredCase.startCase(Map.of("ready", true));

    await()
        .atMost(10, TimeUnit.SECONDS)
        .untilAsserted(() -> assertThat(registry.get(caseId)).isPresent());

    CasePlanModel plan = registry.get(caseId).get();

    PlanItemDefinition.Compound configurerCompound =
        plan.getAllCompounds().stream()
            .filter(c -> c.name().equals("configurer-compound"))
            .findFirst()
            .orElseThrow(() -> new AssertionError("configurer-compound not found in plan model"));

    configuredCase.signal(caseId, "probe", "tick");

    await()
        .atMost(10, TimeUnit.SECONDS)
        .untilAsserted(
            () ->
                assertThat(plan.getDefinitionStatus(configurerCompound.id()))
                    .as("configurer compound with no entry condition must activate on evaluation")
                    .isEqualTo(TaskStatus.RUNNING));
  }

  @ApplicationScoped
  public static class ConfigurerBean implements BlackboardPlanConfigurer {

    @Override
    public void configure(CasePlanModel plan, PlanExecutionContext context) {
      callCount.incrementAndGet();
      plan.registerDefinition(PlanItemDefinition.Compound.builder("configurer-compound").build());
    }
  }

  @ApplicationScoped
  public static class ConfiguredCaseBean extends CaseHub {

    private final Capability cap =
        Capability.builder()
            .name("configured-cap")
            .inputSchema("{ probe: .probe }")
            .outputSchema("{ probe: .probe }")
            .build();

    @Override
    public CaseDefinition getDefinition() {
      return CaseDefinition.builder()
          .namespace("blackboard-configurer-it")
          .name("Configured Case")
          .version("1.0.0")
          .capabilities(cap)
          .workers(
              Worker.builder()
                  .name("configured-worker")
                  .capabilityName("configured-cap")
                  .function(
                      new WorkerFunction.Sync<>(
                          Map.class,
                          Map.class,
                          (input, scope) -> WorkerResult.of(Map.of("probe", "done"))))
                  .build())
          .bindings(
              Binding.builder()
                  .name("on-probe-tick")
                  .capability(cap)
                  .on(new ContextChangeTrigger(".probe == \"tick\""))
                  .build())
          .build();
    }
  }
}
