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
package io.casehub.engine.internal.recovery;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.casehub.api.model.StallRecoveryAction;
import io.casehub.api.model.StallRecoveryContext;
import io.casehub.api.model.StallRecoveryPolicy;
import io.casehub.api.spi.recovery.StallClassificationContext;
import io.casehub.qhorus.api.watchdog.WatchdogConditionType;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class DefaultStallClassifierTest {

  private final DefaultStallClassifier classifier = new DefaultStallClassifier();

  @Test
  void looksUpFromConditionActions() {
    var policy =
        new StallRecoveryPolicy(
            true,
            "policy-lookup",
            Map.of(WatchdogConditionType.LOOP_DETECTED, StallRecoveryAction.CANCEL),
            StallRecoveryAction.NOTIFY);
    var ctx = stallContext(WatchdogConditionType.LOOP_DETECTED, "binding-1", "pi-1");
    var classCtx = new StallClassificationContext(ctx, null, policy);

    assertEquals(StallRecoveryAction.CANCEL, classifier.classify(classCtx));
  }

  @Test
  void fallsBackToDefaultAction() {
    var policy =
        new StallRecoveryPolicy(true, "policy-lookup", Map.of(), StallRecoveryAction.NOTIFY);
    var ctx = stallContext(WatchdogConditionType.QUEUE_DEPTH, null, null);
    var classCtx = new StallClassificationContext(ctx, null, policy);

    assertEquals(StallRecoveryAction.NOTIFY, classifier.classify(classCtx));
  }

  @Test
  void downgradesBindingActionWhenNoBinding() {
    var policy =
        new StallRecoveryPolicy(
            true,
            "policy-lookup",
            Map.of(WatchdogConditionType.AGENT_STALE, StallRecoveryAction.CANCEL),
            StallRecoveryAction.NOTIFY);
    var ctx = stallContext(WatchdogConditionType.AGENT_STALE, null, null);
    var classCtx = new StallClassificationContext(ctx, null, policy);

    assertEquals(StallRecoveryAction.NOTIFY, classifier.classify(classCtx));
  }

  @Test
  void caseActionAllowedWithoutBinding() {
    var policy =
        new StallRecoveryPolicy(
            true,
            "policy-lookup",
            Map.of(WatchdogConditionType.BARRIER_STUCK, StallRecoveryAction.ESCALATE),
            StallRecoveryAction.NOTIFY);
    var ctx = stallContext(WatchdogConditionType.BARRIER_STUCK, null, null);
    var classCtx = new StallClassificationContext(ctx, null, policy);

    assertEquals(StallRecoveryAction.ESCALATE, classifier.classify(classCtx));
  }

  @Test
  void rerouteRequiresBinding() {
    var policy =
        new StallRecoveryPolicy(
            true,
            "policy-lookup",
            Map.of(WatchdogConditionType.ECHO_CHAMBER, StallRecoveryAction.REROUTE),
            StallRecoveryAction.NOTIFY);
    var ctx = stallContext(WatchdogConditionType.ECHO_CHAMBER, null, null);
    var classCtx = new StallClassificationContext(ctx, null, policy);

    assertEquals(StallRecoveryAction.NOTIFY, classifier.classify(classCtx));
  }

  @Test
  void rerouteAllowedWithBinding() {
    var policy =
        new StallRecoveryPolicy(
            true,
            "policy-lookup",
            Map.of(WatchdogConditionType.ECHO_CHAMBER, StallRecoveryAction.REROUTE),
            StallRecoveryAction.NOTIFY);
    var ctx = stallContext(WatchdogConditionType.ECHO_CHAMBER, "binding-1", null);
    var classCtx = new StallClassificationContext(ctx, null, policy);

    assertEquals(StallRecoveryAction.REROUTE, classifier.classify(classCtx));
  }

  private StallRecoveryContext stallContext(
      WatchdogConditionType type, String bindingName, String planItemId) {
    return new StallRecoveryContext(
        UUID.randomUUID(),
        "tenant-1",
        type,
        List.of(),
        "test",
        null,
        Instant.now(),
        bindingName,
        planItemId);
  }
}
