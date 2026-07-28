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
package io.casehub.engine.planning.subcase;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import io.casehub.api.engine.CaseHub;
import io.casehub.api.engine.CaseHubRuntime;
import io.casehub.api.model.Binding;
import io.casehub.api.model.CaseDefinition;
import io.casehub.api.model.CaseStatus;
import io.casehub.api.model.ContextChangeTrigger;
import io.casehub.api.model.OnThresholdReached;
import io.casehub.api.model.SubCase;
import io.casehub.api.model.event.CaseEventLogRecord;
import io.casehub.api.model.event.CaseHubEventType;
import io.casehub.engine.common.internal.model.CaseInstance;
import io.casehub.engine.common.spi.cache.CaseInstanceCache;
import io.casehub.engine.common.spi.event.CaseLifecycleEvent;
import io.casehub.platform.api.identity.TenancyConstants;
import io.casehub.worker.api.Capability;
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
 * Verifies that per-child outputMapping is applied for every completing child in a grouped M-of-N
 * SubCase — not just the child that triggers the threshold. Refs casehubio/engine#574.
 */
@QuarkusTest
class SubCaseMofNOutputMappingTest {

  @Inject OutputMappingParentBean parentCase;
  @Inject CaseInstanceCache caseInstanceCache;
  @Inject SubCaseCompletionListener subCaseCompletionListener;
  @Inject CaseHubRuntime caseHubRuntime;

  @Test
  void bothChildOutputMappings_appliedToParentContext() {
    UUID parentId = parentCase.startCase(Map.of("trigger", "go"));

    await()
        .atMost(15, TimeUnit.SECONDS)
        .until(
            () -> {
              CaseInstance p = caseInstanceCache.get(parentId);
              return p != null && p.getState() == CaseStatus.WAITING;
            });

    List<CaseEventLogRecord> startedEvents =
        caseHubRuntime.eventLog(parentId, Set.of(CaseHubEventType.SUBCASE_STARTED));

    assertThat(startedEvents).as("exactly 2 children must have been spawned").hasSize(2);

    // Resolve which child carries which outputMapping — spawn order is non-deterministic
    // because SUBCASE_SCHEDULE events are published via eventBus.publish() and consumed
    // concurrently on worker threads.
    UUID leftChildId = null;
    UUID rightChildId = null;
    for (CaseEventLogRecord event : startedEvents) {
      UUID childId = UUID.fromString(event.metadata().get("childCaseId").asText());
      String outputMapping = event.metadata().get("outputMapping").asText();
      if (outputMapping.contains("bisectLeft")) {
        leftChildId = childId;
      } else {
        rightChildId = childId;
      }
    }
    assertThat(leftChildId).as("spawn-left child must exist").isNotNull();
    assertThat(rightChildId).as("spawn-right child must exist").isNotNull();

    caseInstanceCache.get(leftChildId).getCaseContext().set("result", "left-value");
    caseInstanceCache.get(rightChildId).getCaseContext().set("result", "right-value");

    // Complete the left child — its outputMapping ({ bisectLeft: .result }) should apply
    // immediately
    subCaseCompletionListener.onCaseLifecycle(completionEvent(leftChildId));

    CaseInstance parentAfterFirst = caseInstanceCache.get(parentId);
    assertThat(parentAfterFirst.getCaseContext().get("bisectLeft"))
        .as("first child's outputMapping must be applied before threshold")
        .isEqualTo("left-value");

    // Complete the right child — triggers threshold (2-of-2) and applies its outputMapping
    subCaseCompletionListener.onCaseLifecycle(completionEvent(rightChildId));

    await()
        .atMost(15, TimeUnit.SECONDS)
        .until(
            () -> {
              CaseInstance p = caseInstanceCache.get(parentId);
              return p != null && p.getState() != CaseStatus.WAITING;
            });

    CaseInstance parentAfterBoth = caseInstanceCache.get(parentId);
    assertThat(parentAfterBoth.getCaseContext().get("bisectLeft"))
        .as("first child's output must survive after threshold")
        .isEqualTo("left-value");
    assertThat(parentAfterBoth.getCaseContext().get("bisectRight"))
        .as("second child's outputMapping must also be applied")
        .isEqualTo("right-value");
  }

  private static CaseLifecycleEvent completionEvent(UUID childId) {
    return CaseLifecycleEvent.of(
        childId,
        TenancyConstants.DEFAULT_TENANT_ID,
        "CompleteCase",
        "CaseCompleted",
        "COMPLETED",
        null,
        "System",
        null);
  }

  // ------------------------------------------------------------------ //
  // Test beans                                                           //
  // ------------------------------------------------------------------ //

  @ApplicationScoped
  public static class OutputMappingChildBean extends CaseHub {

    private static final Capability CAP =
        Capability.builder()
            .name("om-child-cap")
            .inputSchema("{ trigger: .trigger }")
            .outputSchema("{ trigger: .trigger }")
            .build();

    @Override
    public CaseDefinition getDefinition() {
      return CaseDefinition.builder()
          .namespace("test")
          .name("om-child")
          .version("1.0.0")
          .capabilities(CAP)
          .build();
    }
  }

  @ApplicationScoped
  public static class OutputMappingParentBean extends CaseHub {

    @Override
    public CaseDefinition getDefinition() {
      SubCase leftChild =
          SubCase.builder()
              .namespace("test")
              .name("om-child")
              .version("1.0.0")
              .groupId("bisect")
              .totalInGroup(2)
              .requiredCount(2)
              .onThresholdReached(OnThresholdReached.KEEP)
              .inputMapping("{ trigger: .trigger }")
              .outputMapping("{ bisectLeft: .result }")
              .build();

      SubCase rightChild =
          SubCase.builder()
              .namespace("test")
              .name("om-child")
              .version("1.0.0")
              .groupId("bisect")
              .totalInGroup(2)
              .requiredCount(2)
              .onThresholdReached(OnThresholdReached.KEEP)
              .inputMapping("{ trigger: .trigger }")
              .outputMapping("{ bisectRight: .result }")
              .build();

      return CaseDefinition.builder()
          .namespace("test")
          .name("om-parent")
          .version("1.0.0")
          .bindings(
              Binding.builder()
                  .name("spawn-left")
                  .subCase(leftChild)
                  .on(new ContextChangeTrigger(".trigger == \"go\""))
                  .build(),
              Binding.builder()
                  .name("spawn-right")
                  .subCase(rightChild)
                  .on(new ContextChangeTrigger(".trigger == \"go\""))
                  .build())
          .build();
    }
  }
}
