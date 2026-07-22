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
package io.casehub.engine.internal.routing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import io.casehub.api.engine.CaseHub;
import io.casehub.api.model.Binding;
import io.casehub.api.model.CaseDefinition;
import io.casehub.api.model.ContextChangeTrigger;
import io.casehub.api.spi.routing.AgentRoutingContext;
import io.casehub.worker.api.Capability;
import io.casehub.worker.api.Worker;
import io.casehub.worker.api.WorkerFunction;
import io.casehub.worker.api.WorkerResult;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * End-to-end integration test verifying zero-overhead when no CBR config is present: routing
 * strategies receive an empty experience list, and no CBR store interaction occurs.
 *
 * <p>Refs casehubio/engine#478.
 */
@QuarkusTest
@TestProfile(CbrRoutingIntegrationTest.MemoryProfile.class)
class CbrRoutingNoCbrConfigTest {

  @Inject NoCbrCaseHub caseHub;

  @BeforeEach
  void reset() {
    RecordingCbrAgentRoutingStrategy.reset();
  }

  @Test
  void noCbrConfig_experiencesListIsEmpty() {
    caseHub
        .startCase(Map.of("documentId", "doc-no-cbr", "status", "ready"))
        .toCompletableFuture()
        .join();

    // Wait for the recording strategy to be invoked with a processDoc context
    await()
        .atMost(15, TimeUnit.SECONDS)
        .untilAsserted(
            () ->
                assertThat(RecordingCbrAgentRoutingStrategy.capturedContexts)
                    .as("AgentRoutingStrategy must be invoked even without CBR config")
                    .anyMatch(c -> "processDoc".equals(c.capabilityName())));

    AgentRoutingContext ctx =
        RecordingCbrAgentRoutingStrategy.capturedContexts.stream()
            .filter(c -> "processDoc".equals(c.capabilityName()))
            .findFirst()
            .orElseThrow(() -> new AssertionError("No routing context for processDoc capability"));

    assertThat(ctx.experiences())
        .as("Without CBR config, experiences must be an empty list (zero overhead)")
        .isEmpty();
  }

  // ------------------------------------------------------------------ //
  // CaseHub with no CBR config                                           //
  // ------------------------------------------------------------------ //

  @ApplicationScoped
  public static class NoCbrCaseHub extends CaseHub {

    @Override
    public CaseDefinition getDefinition() {
      Capability capability =
          Capability.builder()
              .name("processDoc")
              .inputSchema("{ documentId: .documentId }")
              .outputSchema("{ result: .result }")
              .build();

      Worker worker =
          Worker.builder()
              .name("doc-processor")
              .capabilityName("processDoc")
              .function(
                  new WorkerFunction.Sync<>(
                      Map.class,
                      Map.class,
                      (input, scope) -> WorkerResult.of(Map.of("result", "processed"))))
              .build();

      return CaseDefinition.builder()
          .namespace("test")
          .name("No CBR Test")
          .version("1.0.0")
          .capabilities(capability)
          .workers(worker)
          .bindings(
              Binding.builder()
                  .name("process-on-ready")
                  .capability(capability)
                  .on(new ContextChangeTrigger(".status == \"ready\""))
                  .build())
          .agentRouting("cbr-recording")
          .build();
    }
  }
}
