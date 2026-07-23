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
package io.casehub.engine;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;

import io.casehub.api.engine.CaseHub;
import io.casehub.api.model.CaseDefinition;
import io.casehub.api.model.CaseStatus;
import io.casehub.api.model.Goal;
import io.casehub.api.model.GoalExpression;
import io.casehub.api.model.GoalKind;
import io.casehub.engine.common.spi.cache.CaseInstanceCache;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/**
 * Verifies the signal-to-goal pipeline: a signal directly satisfies a goal condition with no worker
 * or binding intermediary. The case definition has only a goal — no bindings, no workers.
 *
 * <p>Exercises the pipeline that Claudony relies on and confirms casehubio/engine#493 is resolved.
 * Refs casehubio/engine#493.
 */
@QuarkusTest
public class SignalGoalCompletionTest {

  @Inject SignalGoalOnlyBean bean;

  @Inject CaseInstanceCache caseInstanceCache;

  @Test
  void signalDirectlySatisfiesGoalAndCompletesCase() {
    UUID caseId = bean.startCase(Map.of());

    assertEquals(CaseStatus.RUNNING, caseInstanceCache.get(caseId).getState());

    bean.signal(caseId, "done", true);

    await()
        .atMost(5, TimeUnit.SECONDS)
        .untilAsserted(
            () ->
                assertEquals(
                    CaseStatus.COMPLETED,
                    caseInstanceCache.get(caseId).getState(),
                    "Case must transition to COMPLETED when signal satisfies goal"));
  }

  @ApplicationScoped
  public static class SignalGoalOnlyBean extends CaseHub {

    private final Goal doneGoal =
        Goal.builder().name("signalDone").condition(".done == true").kind(GoalKind.SUCCESS).build();

    @Override
    public CaseDefinition getDefinition() {
      return CaseDefinition.builder()
          .namespace("test-signal-goal")
          .name("Signal Goal Only Test")
          .version("1.0.0")
          .goals(doneGoal)
          .completion(GoalExpression.allOf(doneGoal))
          .build();
    }
  }
}
