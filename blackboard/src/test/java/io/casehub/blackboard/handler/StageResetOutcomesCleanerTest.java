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
package io.casehub.blackboard.handler;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.casehub.blackboard.event.StageActivatedEvent;
import io.casehub.blackboard.stage.Stage;
import io.casehub.engine.common.internal.model.CaseInstance;
import io.casehub.engine.common.spi.cache.CaseInstanceCache;
import io.casehub.engine.internal.context.CaseContextImpl;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class StageResetOutcomesCleanerTest {

  private CaseInstanceCache caseInstanceCache;
  private StageResetOutcomesCleaner cleaner;

  private CaseInstance caseInstance;
  private UUID caseId;

  @BeforeEach
  void setUp() {
    caseInstanceCache = mock(CaseInstanceCache.class);
    cleaner = new StageResetOutcomesCleaner();
    cleaner.caseInstanceCache = caseInstanceCache;
    caseId = UUID.randomUUID();
    caseInstance = new CaseInstance();
    caseInstance.setUuid(caseId);
    caseInstance.setCaseContext(new CaseContextImpl());
  }

  @Test
  @SuppressWarnings("unchecked")
  void clearsOutcomesForStageBindingsOnRepetition() {
    caseInstance
        .getCaseContext()
        .set(
            "_outcomes",
            Map.of(
                "binding-a",
                Map.of("status", "DECLINED", "attempts", 1, "excludedAgents", List.of("worker-1")),
                "binding-b",
                Map.of("status", "FAILED", "attempts", 2)));

    when(caseInstanceCache.get(caseId)).thenReturn(caseInstance);

    Stage stage =
        Stage.builder("test-stage")
            .entryCondition(ctx -> true)
            .repeatable(true)
            .binding("binding-a")
            .binding("binding-b")
            .build();

    cleaner.onStageActivated(new StageActivatedEvent(caseId, "test-tenant", stage, 1));

    Map<String, Object> outcomes =
        (Map<String, Object>) caseInstance.getCaseContext().get("_outcomes");
    assertNotNull(outcomes, "_outcomes should still exist");
    assertNull(outcomes.get("binding-a"), "binding-a should be cleared");
    assertNull(outcomes.get("binding-b"), "binding-b should be cleared");
  }

  @Test
  @SuppressWarnings("unchecked")
  void preservesOutcomesForOtherBindings() {
    caseInstance
        .getCaseContext()
        .set(
            "_outcomes",
            Map.of(
                "stage-binding", Map.of("status", "DECLINED"),
                "other-binding", Map.of("status", "FAILED")));

    when(caseInstanceCache.get(caseId)).thenReturn(caseInstance);

    Stage stage =
        Stage.builder("test-stage")
            .entryCondition(ctx -> true)
            .repeatable(true)
            .binding("stage-binding")
            .build();

    cleaner.onStageActivated(new StageActivatedEvent(caseId, "test-tenant", stage, 2));

    Map<String, Object> outcomes =
        (Map<String, Object>) caseInstance.getCaseContext().get("_outcomes");
    assertNotNull(outcomes);
    assertNull(outcomes.get("stage-binding"), "stage-binding should be cleared");
    assertNotNull(outcomes.get("other-binding"), "other-binding should be preserved");
    assertEquals("FAILED", ((Map<String, Object>) outcomes.get("other-binding")).get("status"));
  }

  @Test
  void skipsFirstActivation() {
    caseInstance
        .getCaseContext()
        .set("_outcomes", Map.of("binding-a", Map.of("status", "DECLINED")));

    Stage stage =
        Stage.builder("test-stage")
            .entryCondition(ctx -> true)
            .repeatable(true)
            .binding("binding-a")
            .build();

    cleaner.onStageActivated(new StageActivatedEvent(caseId, "test-tenant", stage, 0));

    assertNotNull(
        caseInstance.getCaseContext().get("_outcomes"), "_outcomes should not be modified");
  }

  @Test
  void handlesNoOutcomesGracefully() {
    when(caseInstanceCache.get(caseId)).thenReturn(caseInstance);

    Stage stage =
        Stage.builder("test-stage")
            .entryCondition(ctx -> true)
            .repeatable(true)
            .binding("binding-a")
            .build();

    cleaner.onStageActivated(new StageActivatedEvent(caseId, "test-tenant", stage, 1));

    assertNull(caseInstance.getCaseContext().get("_outcomes"), "_outcomes should remain null");
  }
}
