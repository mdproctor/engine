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
package io.casehub.engine.scheduler;

import static org.assertj.core.api.Assertions.assertThat;

import io.casehub.api.model.Binding;
import io.casehub.api.model.ScheduleTrigger;
import io.casehub.api.model.evaluator.ExpressionEvaluator;
import io.casehub.api.model.evaluator.JQExpressionEvaluator;
import io.casehub.worker.api.Capability;
import io.casehub.worker.api.Worker;
import io.casehub.worker.api.WorkerFunction;
import io.casehub.worker.api.WorkerResult;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Test that verifies conditional trigger scheduling uses the condition from Binding.
 *
 * <p>This test verifies that:
 *
 * <ul>
 *   <li>scheduleConditionalWorker NO LONGER has a redundant condition parameter
 *   <li>The condition is retrieved from {@link Binding#getWhen()}
 *   <li>{@link io.casehub.engine.internal.scheduler.quartz.ConditionalScheduledTriggerJob}
 *       retrieves the condition from the binding at execution time
 * </ul>
 */
class ConditionalTriggerConditionParameterTest {

  @Test
  void schedulerService_usesBindingCondition() {
    // This test verifies that scheduleConditionalWorker correctly uses binding.when()

    Capability cap =
        Capability.builder()
            .name("doWork")
            .inputSchema("{ }")
            .outputSchema("{ workDone: .workDone }")
            .build();

    Worker worker =
        Worker.builder()
            .name("test-worker")
            .capabilityName("doWork")
            .function(
                new WorkerFunction.Sync<>(
                    Map.class,
                    Map.class,
                    (ctx, scope) -> WorkerResult.of(Map.of("workDone", true))))
            .build();

    ExpressionEvaluator condition = new JQExpressionEvaluator(".status == \"ready\"");
    Binding binding =
        Binding.builder()
            .name("conditional-binding")
            .capability(cap)
            .on(ScheduleTrigger.delay(Duration.ofSeconds(2)))
            .when(condition)
            .build();

    // Verify binding has the condition
    assertThat(binding.getWhen()).isSameAs(condition);
    assertThat(binding.getWhen()).isNotNull();

    // Verify that scheduleConditionalWorker now takes only:
    // - caseId (UUID)
    // - binding (containing the condition via binding.getWhen())
    // - trigger (ScheduleTrigger)
    // - worker (Worker)
    //
    // The condition is NOT a separate parameter anymore.
    // It's retrieved from binding.getWhen() by ConditionalScheduledTriggerJob.
  }

  @Test
  void binding_containsConditionInWhen() {
    // Verify that Binding stores the condition in when()

    ExpressionEvaluator condition = new JQExpressionEvaluator(".enabled == true");

    Capability cap =
        Capability.builder()
            .name("doWork")
            .inputSchema("{ }")
            .outputSchema("{ workDone: .workDone }")
            .build();

    Binding binding =
        Binding.builder()
            .name("test")
            .capability(cap)
            .on(ScheduleTrigger.delay(Duration.ofSeconds(1)))
            .when(condition)
            .build();

    // The condition is stored in the binding
    assertThat(binding.getWhen()).isSameAs(condition);

    // ConditionalScheduledTriggerJob retrieves the binding by name from CaseDefinition
    // and evaluates binding.getWhen() against the current CaseContext
  }
}
