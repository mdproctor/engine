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

import static org.assertj.core.api.Assertions.assertThat;

import io.casehub.api.model.CaseDefinition;
import io.casehub.api.model.Goal;
import io.casehub.api.model.GoalExpression;
import io.casehub.api.model.GoalKind;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

@QuarkusTest
class DefaultCaseDefinitionRegistryGoalWarningTest {

  @Inject DefaultCaseDefinitionRegistry registry;

  private final List<LogRecord> logRecords = new ArrayList<>();
  private Handler testHandler;
  private Logger logger;

  @BeforeEach
  void setupLogCapture() {
    logger = Logger.getLogger(DefaultCaseDefinitionRegistry.class.getName());
    logger.setLevel(Level.WARNING);
    testHandler =
        new Handler() {
          @Override
          public void publish(LogRecord record) {
            logRecords.add(record);
          }

          @Override
          public void flush() {}

          @Override
          public void close() {}
        };
    logger.addHandler(testHandler);
  }

  @AfterEach
  void teardownLogCapture() {
    if (testHandler != null && logger != null) {
      logger.removeHandler(testHandler);
    }
    logRecords.clear();
  }

  @Test
  void warns_when_goal_not_referenced_in_any_goal_expression() {
    var unreferencedGoal =
        Goal.builder()
            .name("orphan-goal")
            .condition(".orphan == true")
            .kind(GoalKind.SUCCESS)
            .build();

    var referencedGoal =
        Goal.builder().name("real-goal").condition(".done == true").kind(GoalKind.SUCCESS).build();

    var definition =
        CaseDefinition.builder()
            .namespace("test")
            .name("warn-test")
            .version("1.0")
            .goals(List.of(unreferencedGoal, referencedGoal))
            .completion(GoalExpression.allOf(referencedGoal), null)
            .build();

    registry
        .registerCaseDefinition(definition)
        .subscribe()
        .asCompletionStage()
        .toCompletableFuture()
        .join();

    assertThat(logRecords)
        .anyMatch(
            r ->
                r.getMessage().contains("orphan-goal")
                    && r.getMessage().contains("not referenced"));
    assertThat(logRecords).noneMatch(r -> r.getMessage().contains("real-goal"));
  }

  @Test
  void does_not_warn_when_goal_referenced_in_success_expression() {
    var successGoal =
        Goal.builder()
            .name("success-goal")
            .condition(".success == true")
            .kind(GoalKind.SUCCESS)
            .build();

    var definition =
        CaseDefinition.builder()
            .namespace("test")
            .name("success-ref-test")
            .version("1.0")
            .goals(List.of(successGoal))
            .completion(GoalExpression.allOf(successGoal), null)
            .build();

    registry
        .registerCaseDefinition(definition)
        .subscribe()
        .asCompletionStage()
        .toCompletableFuture()
        .join();

    assertThat(logRecords).noneMatch(r -> r.getMessage().contains("success-goal"));
  }

  @Test
  void does_not_warn_when_goal_referenced_in_failure_expression() {
    var failureGoal =
        Goal.builder()
            .name("failure-goal")
            .condition(".failed == true")
            .kind(GoalKind.FAILURE)
            .build();

    var definition =
        CaseDefinition.builder()
            .namespace("test")
            .name("failure-ref-test")
            .version("1.0")
            .goals(List.of(failureGoal))
            .completion(null, GoalExpression.allOf(failureGoal))
            .build();

    registry
        .registerCaseDefinition(definition)
        .subscribe()
        .asCompletionStage()
        .toCompletableFuture()
        .join();

    assertThat(logRecords).noneMatch(r -> r.getMessage().contains("failure-goal"));
  }

  @Test
  void warns_kind_mismatch_when_goal_referenced_in_wrong_completion_entry() {
    var sharedGoal =
        Goal.builder()
            .name("shared-goal")
            .condition(".shared == true")
            .kind(GoalKind.SUCCESS)
            .build();

    var definition =
        CaseDefinition.builder()
            .namespace("test")
            .name("both-ref-test")
            .version("1.0")
            .goals(List.of(sharedGoal))
            .completion(GoalExpression.allOf(sharedGoal), GoalExpression.allOf(sharedGoal))
            .build();

    registry
        .registerCaseDefinition(definition)
        .subscribe()
        .asCompletionStage()
        .toCompletableFuture()
        .join();

    assertThat(logRecords).noneMatch(r -> r.getMessage().contains("not referenced"));
    assertThat(logRecords)
        .anyMatch(
            r ->
                r.getMessage().contains("shared-goal") && r.getMessage().contains("kind mismatch"));
  }
}
