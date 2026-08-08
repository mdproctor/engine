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
package io.casehub.engine.plan.adaptation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class AdaptationTypesTest {

  @Test
  void planStepDescriptorRejectsNullId() {
    assertThrows(NullPointerException.class, () -> new PlanStepDescriptor(null, "desc", "cap"));
  }

  @Test
  void planStepDescriptorRejectsNullCapabilityName() {
    assertThrows(NullPointerException.class, () -> new PlanStepDescriptor("id", "desc", null));
  }

  @Test
  void planStepDescriptorStoresFields() {
    var step = new PlanStepDescriptor("s1", "gather data", "data-gathering");
    assertEquals("s1", step.id());
    assertEquals("gather data", step.description());
    assertEquals("data-gathering", step.capabilityName());
  }

  @Test
  void completedStepRejectsNullStepId() {
    assertThrows(
        NullPointerException.class,
        () -> new CompletedStep(null, "cap", "desc", Map.of(), Instant.now()));
  }

  @Test
  void completedStepRejectsNullCompletedAt() {
    assertThrows(
        NullPointerException.class, () -> new CompletedStep("id", "cap", "desc", Map.of(), null));
  }

  @Test
  void completedStepOutputDefaultsToEmptyMap() {
    var step = new CompletedStep("id", "cap", "desc", null, Instant.now());
    assertNotNull(step.output());
    assertEquals(0, step.output().size());
  }

  @Test
  void completedStepOutputIsImmutable() {
    var step = new CompletedStep("id", "cap", "desc", Map.of("k", "v"), Instant.now());
    assertThrows(UnsupportedOperationException.class, () -> step.output().put("x", "y"));
  }

  @Test
  void adaptationSignalProceedInstance() {
    assertInstanceOf(AdaptationSignal.Proceed.class, AdaptationSignal.PROCEED);
  }

  @Test
  void adaptationSignalSkipInstance() {
    assertInstanceOf(AdaptationSignal.Skip.class, AdaptationSignal.SKIP);
  }

  @Test
  void adaptationCauseStepCompleted() {
    var cause = new AdaptationCause.StepCompleted("s1", "cap", Map.of("r", "v"));
    assertEquals("s1", cause.stepId());
    assertEquals("cap", cause.capabilityName());
    assertEquals(Map.of("r", "v"), cause.output());
  }

  @Test
  void adaptationCauseStepFailed() {
    var cause = new AdaptationCause.StepFailed("s1", "timeout");
    assertEquals("s1", cause.stepId());
    assertEquals("timeout", cause.reason());
  }

  @Test
  void adaptationCauseStepCompletedRejectsNullStepId() {
    assertThrows(
        NullPointerException.class, () -> new AdaptationCause.StepCompleted(null, "cap", Map.of()));
  }

  @Test
  void adaptationCauseStepFailedRejectsNullReason() {
    assertThrows(NullPointerException.class, () -> new AdaptationCause.StepFailed("s1", null));
  }

  @Test
  void revisedPlanStepsImmutable() {
    var plan = new RevisedPlan(List.of(new PlanStepDescriptor("id", "desc", "cap")), "reason");
    assertThrows(
        UnsupportedOperationException.class,
        () -> plan.steps().add(new PlanStepDescriptor("x", "y", "z")));
  }

  @Test
  void revisedPlanRejectsNullSteps() {
    assertThrows(NullPointerException.class, () -> new RevisedPlan(null, "reason"));
  }

  @Test
  void revisedPlanAllowsNullRationale() {
    var plan = new RevisedPlan(List.of(new PlanStepDescriptor("id", "desc", "cap")), null);
    assertEquals(1, plan.steps().size());
  }
}
