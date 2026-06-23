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
package io.casehub.engine.internal.engine;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.casehub.api.model.Binding;
import io.casehub.api.model.CaseDefinition;
import io.casehub.api.model.ContextChangeTrigger;
import io.casehub.api.model.Goal;
import io.casehub.api.model.GoalKind;
import io.casehub.api.model.Milestone;
import io.casehub.api.model.evaluator.JQExpressionEvaluator;
import io.casehub.engine.common.spi.CaseDefinitionRegistry;
import io.casehub.worker.api.Capability;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@QuarkusTest
@DisplayName("CaseDefinitionRegistry — expression validation")
class CaseDefinitionRegistryValidationTest {

  @Inject CaseDefinitionRegistry registry;

  private static final Capability CAPABILITY =
      Capability.builder()
          .name("test-capability")
          .inputSchema("{ id: .id }")
          .outputSchema("{ result: . }")
          .description("Test capability")
          .build();

  @Test
  @DisplayName("invalid JQ in rule trigger filter blocks registration")
  void invalidJQInTriggerFilter_blocksRegistration() {
    final var definition =
        CaseDefinition.builder()
            .namespace("test-validation")
            .name("bad-trigger-filter")
            .version("99.0.0")
            .bindings(
                Binding.builder()
                    .name("bad-rule")
                    .capability(CAPABILITY)
                    .on(new ContextChangeTrigger(".status ??? invalid"))
                    .build())
            .build();

    final var ex =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                registry.registerCaseDefinition(definition).await().atMost(Duration.ofSeconds(5)));
    assertTrue(ex.getMessage().contains("Invalid JQ expression"));
  }

  @Test
  @DisplayName("invalid JQ in rule when-condition blocks registration")
  void invalidJQInWhenCondition_blocksRegistration() {
    final var definition =
        CaseDefinition.builder()
            .namespace("test-validation")
            .name("bad-when-condition")
            .version("99.0.0")
            .bindings(
                Binding.builder()
                    .name("bad-rule")
                    .capability(CAPABILITY)
                    .on(new ContextChangeTrigger(".status == \"ready\""))
                    .when(new JQExpressionEvaluator(".count ??? broken"))
                    .build())
            .build();

    final var ex =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                registry.registerCaseDefinition(definition).await().atMost(Duration.ofSeconds(5)));
    assertTrue(ex.getMessage().contains("Invalid JQ expression"));
  }

  @Test
  @DisplayName("invalid JQ in goal condition blocks registration")
  void invalidJQInGoalCondition_blocksRegistration() {
    final var definition =
        CaseDefinition.builder()
            .namespace("test-validation")
            .name("bad-goal")
            .version("99.0.0")
            .goals(
                Goal.builder()
                    .name("bad-goal")
                    .condition(new JQExpressionEvaluator(".result ??? oops"))
                    .kind(GoalKind.SUCCESS)
                    .build())
            .build();

    final var ex =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                registry.registerCaseDefinition(definition).await().atMost(Duration.ofSeconds(5)));
    assertTrue(ex.getMessage().contains("Invalid JQ expression"));
  }

  @Test
  @DisplayName("invalid JQ in milestone condition blocks registration")
  void invalidJQInMilestoneCondition_blocksRegistration() {
    final var definition =
        CaseDefinition.builder()
            .namespace("test-validation")
            .name("bad-milestone")
            .version("99.0.0")
            .milestones(
                Milestone.builder()
                    .name("bad-milestone")
                    .completionCriteria(new JQExpressionEvaluator(".phase ??? nope"))
                    .build())
            .build();

    final var ex =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                registry.registerCaseDefinition(definition).await().atMost(Duration.ofSeconds(5)));
    assertTrue(ex.getMessage().contains("Invalid JQ expression"));
  }
}
