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
import io.casehub.api.model.CaseDefinition;
import io.casehub.api.model.CaseStatus;
import io.casehub.api.model.ContextChangeTrigger;
import io.casehub.api.model.SubCase;
import io.casehub.engine.common.spi.cache.CaseInstanceCache;
import io.casehub.worker.api.Capability;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/**
 * Integration test for the SubCaseBinding path. Verifies that when a parent case fires a
 * SubCase-backed Binding, the parent transitions to WAITING while the child case runs.
 *
 * <p>See casehubio/engine#195.
 */
@QuarkusTest
class SubCaseIntegrationTest {

  @Inject ParentCaseBean parentCase;
  @Inject CaseInstanceCache caseInstanceCache;

  @Test
  void subCaseBinding_parentTransitionsToWaiting_whenWaitForCompletionTrue() {
    UUID parentId =
        parentCase
            .startCase(Map.of("trigger", "go", "status", "pending"))
            .toCompletableFuture()
            .join();

    // Parent should transition to WAITING when child is spawned
    await()
        .atMost(10, TimeUnit.SECONDS)
        .until(
            () ->
                caseInstanceCache.get(parentId) != null
                    && caseInstanceCache.get(parentId).getState() == CaseStatus.WAITING);

    assertThat(caseInstanceCache.get(parentId).getState())
        .as("Parent case should be WAITING after spawning child")
        .isEqualTo(CaseStatus.WAITING);
  }

  // ------------------------------------------------------------------ //
  // Test beans                                                           //
  // ------------------------------------------------------------------ //

  /**
   * Minimal child case with a capability. Registered so the CaseDefinitionRegistry can resolve it
   * when the parent's SubCaseBinding fires.
   */
  @ApplicationScoped
  public static class ChildCaseBean extends CaseHub {

    private final Capability cap =
        Capability.builder()
            .name("child-work")
            .inputSchema("{ status: .status }")
            .outputSchema("{ status: .status }")
            .build();

    @Override
    public CaseDefinition getDefinition() {
      return CaseDefinition.builder()
          .namespace("test")
          .name("child-case")
          .version("1.0.0")
          .capabilities(cap)
          .build();
    }
  }

  /**
   * Parent case with a SubCase binding that fires when {@code .trigger == "go"}. Spawns a child
   * case and sets {@code waitForCompletion=true} so the parent transitions to WAITING.
   */
  @ApplicationScoped
  public static class ParentCaseBean extends CaseHub {

    @Override
    public CaseDefinition getDefinition() {
      SubCase child =
          SubCase.builder()
              .namespace("test")
              .name("child-case")
              .version("1.0.0")
              .waitForCompletion(true)
              .inputMapping("{ status: .status }")
              .build();

      return CaseDefinition.builder()
          .namespace("test")
          .name("parent-case")
          .version("1.0.0")
          .bindings(
              Binding.builder()
                  .name("spawn-child")
                  .subCase(child)
                  .on(new ContextChangeTrigger(".trigger == \"go\""))
                  .build())
          .build();
    }
  }
}
