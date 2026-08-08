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

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import io.casehub.api.model.CaseDefinition;
import io.casehub.api.model.TaskStatus;
import io.casehub.engine.plan.adaptation.AdaptationContext;
import io.casehub.engine.plan.adaptation.AdaptationSignal;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AdaptationTriggerTest {

  private AdaptationContext buildContext(TaskStatus status) {
    return new AdaptationContext(
        UUID.randomUUID(),
        "tenant-1",
        "goal-compound",
        "research-topic",
        List.of(),
        List.of(),
        List.of(),
        JsonNodeFactory.instance.objectNode(),
        CaseDefinition.builder().namespace("test").name("test").version("1.0").build(),
        status,
        "cap-a",
        0);
  }

  @Test
  void everyStepTriggerAlwaysProceeds() {
    var trigger = new EveryStepTrigger();
    assertEquals(AdaptationSignal.PROCEED, trigger.evaluate(buildContext(TaskStatus.COMPLETED)));
  }

  @Test
  void everyStepTriggerProceedsOnFaulted() {
    var trigger = new EveryStepTrigger();
    assertEquals(AdaptationSignal.PROCEED, trigger.evaluate(buildContext(TaskStatus.FAULTED)));
  }

  @Test
  void everyStepTriggerIdIsEveryStep() {
    assertEquals("every-step", new EveryStepTrigger().id());
  }

  @Test
  void onFailureTriggerSkipsOnCompleted() {
    var trigger = new OnFailureTrigger();
    assertEquals(AdaptationSignal.SKIP, trigger.evaluate(buildContext(TaskStatus.COMPLETED)));
  }

  @Test
  void onFailureTriggerProceedsOnFaulted() {
    var trigger = new OnFailureTrigger();
    assertEquals(AdaptationSignal.PROCEED, trigger.evaluate(buildContext(TaskStatus.FAULTED)));
  }

  @Test
  void onFailureTriggerProceedsOnRejected() {
    var trigger = new OnFailureTrigger();
    assertEquals(AdaptationSignal.PROCEED, trigger.evaluate(buildContext(TaskStatus.REJECTED)));
  }

  @Test
  void onFailureTriggerProceedsOnCancelled() {
    var trigger = new OnFailureTrigger();
    assertEquals(AdaptationSignal.PROCEED, trigger.evaluate(buildContext(TaskStatus.CANCELLED)));
  }

  @Test
  void onFailureTriggerSkipsOnPending() {
    var trigger = new OnFailureTrigger();
    assertEquals(AdaptationSignal.SKIP, trigger.evaluate(buildContext(TaskStatus.PENDING)));
  }

  @Test
  void onFailureTriggerSkipsOnRunning() {
    var trigger = new OnFailureTrigger();
    assertEquals(AdaptationSignal.SKIP, trigger.evaluate(buildContext(TaskStatus.RUNNING)));
  }

  @Test
  void onFailureTriggerIdIsOnFailure() {
    assertEquals("on-failure", new OnFailureTrigger().id());
  }
}
