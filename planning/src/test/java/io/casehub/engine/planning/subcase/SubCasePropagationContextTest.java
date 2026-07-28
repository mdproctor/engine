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
import io.casehub.api.model.Binding;
import io.casehub.api.model.CaseDefinition;
import io.casehub.api.model.CaseStatus;
import io.casehub.api.model.ContextChangeTrigger;
import io.casehub.api.model.SubCase;
import io.casehub.engine.common.internal.model.CaseInstance;
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
 * Verifies that a child case inherits the parent's trace ID and has its parentCaseId set correctly.
 *
 * <p>See casehubio/engine#112.
 */
@QuarkusTest
class SubCasePropagationContextTest {

  @Inject PropTestParentBean parentCase;
  @Inject CaseInstanceCache caseInstanceCache;

  @Test
  void child_inherits_traceId_and_parentCaseId() {
    UUID parentId = parentCase.startCase(Map.of("trigger", "go"));

    // Wait for parent to go WAITING (child spawned, waitForCompletion=true)
    await()
        .atMost(10, TimeUnit.SECONDS)
        .until(
            () -> {
              CaseInstance p = caseInstanceCache.get(parentId);
              return p != null && p.getState() == CaseStatus.WAITING;
            });

    CaseInstance parent = caseInstanceCache.get(parentId);
    String parentTraceId = parent.getPropagationContext().getTraceId();

    // Ungrouped child — waitingForWorkId is the childCaseId
    UUID childId = UUID.fromString(parent.getWaitingForWorkId());
    CaseInstance child = caseInstanceCache.get(childId);

    assertThat(child).isNotNull();
    assertThat(child.getParentCaseId())
        .as("child.parentCaseId must equal parent UUID")
        .isEqualTo(parentId);
    assertThat(child.getPropagationContext().getTraceId())
        .as("child must inherit parent's trace ID")
        .isEqualTo(parentTraceId);

    // Identity propagation (engine#455): userId and roles must flow from root to child
    assertThat(parent.getPropagationContext().getAttribute("userId"))
        .as("parent must carry userId from CurrentPrincipal")
        .isPresent();
    assertThat(parent.getPropagationContext().getAttribute("roles"))
        .as("parent must carry roles from CurrentPrincipal")
        .isPresent();
    assertThat(child.getPropagationContext().getAttribute("userId"))
        .as("child must inherit userId from parent")
        .hasValue(parent.getPropagationContext().getAttribute("userId").orElseThrow());
    assertThat(child.getPropagationContext().getAttribute("roles"))
        .as("child must inherit roles from parent")
        .hasValue(parent.getPropagationContext().getAttribute("roles").orElseThrow());
  }

  // ------------------------------------------------------------------ //
  // Test beans                                                           //
  // ------------------------------------------------------------------ //

  /**
   * Minimal child with a Capability but no Goal — stays RUNNING indefinitely. This test only checks
   * that propagation context fields are correctly set when the child starts, so no completion
   * mechanism is needed.
   */
  @ApplicationScoped
  public static class PropTestChildBean extends CaseHub {

    private static final Capability CAP =
        Capability.builder()
            .name("prop-child-cap")
            .inputSchema("{ trigger: .trigger }")
            .outputSchema("{ trigger: .trigger }")
            .build();

    @Override
    public CaseDefinition getDefinition() {
      return CaseDefinition.builder()
          .namespace("test")
          .name("prop-child")
          .version("1.0.0")
          .capabilities(CAP)
          .build();
    }
  }

  /** Parent that spawns {@code prop-child} and waits for it. */
  @ApplicationScoped
  public static class PropTestParentBean extends CaseHub {

    @Override
    public CaseDefinition getDefinition() {
      SubCase child =
          SubCase.builder()
              .namespace("test")
              .name("prop-child")
              .version("1.0.0")
              .waitForCompletion(true)
              .inputMapping("{ trigger: .trigger }")
              .build();
      return CaseDefinition.builder()
          .namespace("test")
          .name("prop-parent")
          .version("1.0.0")
          .bindings(
              Binding.builder()
                  .name("spawn-prop-child")
                  .subCase(child)
                  .on(new ContextChangeTrigger(".trigger == \"go\""))
                  .build())
          .build();
    }
  }
}
