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
package io.casehub.blackboard.subcase;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import io.casehub.api.engine.CaseHub;
import io.casehub.api.model.Binding;
import io.casehub.api.model.Capability;
import io.casehub.api.model.CaseDefinition;
import io.casehub.api.model.CaseStatus;
import io.casehub.api.model.ContextChangeTrigger;
import io.casehub.api.model.OnThresholdReached;
import io.casehub.api.model.SubCase;
import io.casehub.api.model.event.CaseHubEventType;
import io.casehub.engine.common.internal.model.CaseInstance;
import io.casehub.engine.common.spi.cache.CaseInstanceCache;
import io.casehub.engine.common.spi.event.CaseLifecycleEvent;
import io.casehub.platform.api.identity.TenancyConstants;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/**
 * Tests the allOf(3) grouped SubCase path — all 3 children must complete before the parent resumes.
 *
 * <p>Implementation note: CDI {@code @ObservesAsync CaseLifecycleEvent} delivery is unreliable in
 * the Quarkus test context (see casehubio/engine#112). We invoke {@link
 * SubCaseCompletionListener#onCaseLifecycle} directly to test the listener's grouped-path logic
 * without depending on CDI async event plumbing.
 *
 * <p>See casehubio/engine#112.
 */
@QuarkusTest
class SubCaseParallelIntegrationTest {

  @Inject AllOfParentBean parentCase;
  @Inject CaseInstanceCache caseInstanceCache;
  @Inject SubCaseCompletionListener subCaseCompletionListener;
  @Inject io.casehub.api.engine.CaseHubRuntime caseHubRuntime;

  @Test
  void allOf3_allComplete_parentResumes() {
    UUID parentId = parentCase.startCase(Map.of("trigger", "go")).toCompletableFuture().join();

    // Wait for parent to go WAITING — all 3 children spawned and group registered
    await()
        .atMost(15, TimeUnit.SECONDS)
        .until(
            () -> {
              CaseInstance p = caseInstanceCache.get(parentId);
              return p != null && p.getState() == CaseStatus.WAITING;
            });

    // Discover child IDs from the EventLog
    List<UUID> childIds =
        caseHubRuntime
            .eventLog(parentId, Set.of(CaseHubEventType.SUBCASE_STARTED))
            .toCompletableFuture()
            .join()
            .stream()
            .map(r -> UUID.fromString(r.metadata().get("childCaseId").asText()))
            .toList();

    assertThat(childIds).as("exactly 3 children must have been spawned").hasSize(3);

    // Simulate all 3 children completing by invoking the listener directly.
    // This bypasses CDI @ObservesAsync delivery which is unreliable in the test context.
    CaseLifecycleEvent completedEvent0 =
        new CaseLifecycleEvent(
            childIds.get(0),
            TenancyConstants.DEFAULT_TENANT_ID,
            "CompleteCase",
            "CaseCompleted",
            "COMPLETED",
            null,
            "System",
            null);
    CaseLifecycleEvent completedEvent1 =
        new CaseLifecycleEvent(
            childIds.get(1),
            TenancyConstants.DEFAULT_TENANT_ID,
            "CompleteCase",
            "CaseCompleted",
            "COMPLETED",
            null,
            "System",
            null);
    CaseLifecycleEvent completedEvent2 =
        new CaseLifecycleEvent(
            childIds.get(2),
            TenancyConstants.DEFAULT_TENANT_ID,
            "CompleteCase",
            "CaseCompleted",
            "COMPLETED",
            null,
            "System",
            null);

    subCaseCompletionListener.onCaseLifecycle(completedEvent0);
    subCaseCompletionListener.onCaseLifecycle(completedEvent1);
    subCaseCompletionListener.onCaseLifecycle(completedEvent2);

    // Parent must resume (leave WAITING) after all 3 complete
    await()
        .atMost(15, TimeUnit.SECONDS)
        .until(
            () -> {
              CaseInstance p = caseInstanceCache.get(parentId);
              return p != null && p.getState() != CaseStatus.WAITING;
            });

    assertThat(caseInstanceCache.get(parentId).getState())
        .as("parent must leave WAITING once all 3 children complete")
        .isNotEqualTo(CaseStatus.WAITING);
  }

  // ------------------------------------------------------------------ //
  // Test beans                                                           //
  // ------------------------------------------------------------------ //

  /**
   * Minimal child with a Capability but no Goal — stays RUNNING until cancelled. This prevents the
   * child from auto-completing before the test signals completion via the listener.
   */
  @ApplicationScoped
  public static class AllOfChildBean extends CaseHub {

    private static final Capability CAP =
        Capability.builder()
            .name("allof-child-cap")
            .inputSchema("{ trigger: .trigger }")
            .outputSchema("{ trigger: .trigger }")
            .build();

    @Override
    public CaseDefinition getDefinition() {
      return CaseDefinition.builder()
          .namespace("test")
          .name("allof-child")
          .version("1.0.0")
          .capabilities(CAP)
          .build();
    }
  }

  /**
   * Parent that spawns 3 grouped children ({@code totalInGroup=3, requiredCount=3}) and waits for
   * all of them. Uses {@link OnThresholdReached#KEEP} so surviving children are not cancelled.
   */
  @ApplicationScoped
  public static class AllOfParentBean extends CaseHub {

    @Override
    public CaseDefinition getDefinition() {
      SubCase child =
          SubCase.builder()
              .namespace("test")
              .name("allof-child")
              .version("1.0.0")
              .groupId("allof-sites")
              .totalInGroup(3)
              .requiredCount(3)
              .onThresholdReached(OnThresholdReached.KEEP)
              .inputMapping("{ trigger: .trigger }")
              .build();

      return CaseDefinition.builder()
          .namespace("test")
          .name("allof-parent")
          .version("1.0.0")
          .bindings(
              Binding.builder()
                  .name("spawn-site-a")
                  .subCase(child)
                  .on(new ContextChangeTrigger(".trigger == \"go\""))
                  .build(),
              Binding.builder()
                  .name("spawn-site-b")
                  .subCase(child)
                  .on(new ContextChangeTrigger(".trigger == \"go\""))
                  .build(),
              Binding.builder()
                  .name("spawn-site-c")
                  .subCase(child)
                  .on(new ContextChangeTrigger(".trigger == \"go\""))
                  .build())
          .build();
    }
  }
}
