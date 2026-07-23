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

import io.casehub.api.context.ContextLayer;
import io.casehub.api.engine.CaseHub;
import io.casehub.api.model.Binding;
import io.casehub.api.model.CaseDefinition;
import io.casehub.api.model.ContextChangeTrigger;
import io.casehub.api.model.cbr.CbrConfig;
import io.casehub.api.spi.routing.AgentRoutingContext;
import io.casehub.api.spi.routing.RetrievedExperience;
import io.casehub.neocortex.memory.MemoryDomain;
import io.casehub.neocortex.memory.cbr.CbrCaseMemoryStore;
import io.casehub.neocortex.memory.cbr.FeatureValue;
import io.casehub.neocortex.memory.cbr.PlanCbrCase;
import io.casehub.neocortex.memory.cbr.PlanTrace;
import io.casehub.platform.api.identity.TenancyConstants;
import io.casehub.worker.api.Capability;
import io.casehub.worker.api.Worker;
import io.casehub.worker.api.WorkerFunction;
import io.casehub.worker.api.WorkerResult;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * End-to-end integration test verifying the Java DSL path for CBR retrieval: a CaseHub subclass
 * uses {@code CaseDefinition.builder().cbrConfig(CbrConfig.builder().featureExtractor(lambda)...)}
 * and the recording AgentRoutingStrategy verifies that lambda-extracted features produce correct
 * experiences at routing time.
 *
 * <p>Refs casehubio/engine#478.
 */
@QuarkusTest
@TestProfile(CbrRoutingIntegrationTest.MemoryProfile.class)
class CbrRoutingFuncDslIntegrationTest {

  @Inject FuncDslCbrCaseHub caseHub;
  @Inject CbrCaseMemoryStore cbrStore;

  @BeforeEach
  void reset() {
    RecordingCbrAgentRoutingStrategy.reset();
  }

  @Test
  void lambdaFeatureExtractor_experiencesReachAgentRoutingStrategy() {
    // Pre-load the CBR store with a matching case
    PlanTrace trace =
        new PlanTrace("analyse-risk", "assessRisk", "risk-assessor", "SUCCESS", 0, Map.of());
    PlanCbrCase pastCase =
        new PlanCbrCase(
            "High-risk transaction detected",
            "Flag for manual review",
            "COMPLETED",
            0.88,
            Map.of("riskLevel", FeatureValue.string("high"), "amount", FeatureValue.number(50000)),
            List.of(trace));
    cbrStore.store(
        pastCase,
        "risk-assessment",
        "entity-2",
        new MemoryDomain("func-dsl-domain"),
        TenancyConstants.DEFAULT_TENANT_ID,
        UUID.randomUUID().toString(),
        io.casehub.platform.api.path.Path.root());

    // Start the case with context that triggers the binding
    caseHub.startCase(
        Map.of("transaction", Map.of("riskLevel", "high", "amount", 45000, "currency", "USD")));

    // Wait for the recording strategy to be invoked with an assessRisk context
    await()
        .atMost(15, TimeUnit.SECONDS)
        .untilAsserted(
            () ->
                assertThat(RecordingCbrAgentRoutingStrategy.capturedContexts)
                    .as("AgentRoutingStrategy must be invoked with CBR experiences")
                    .anyMatch(c -> "assessRisk".equals(c.capabilityName())));

    // Verify the captured experiences for the assessRisk capability
    AgentRoutingContext ctx =
        RecordingCbrAgentRoutingStrategy.capturedContexts.stream()
            .filter(c -> "assessRisk".equals(c.capabilityName()))
            .findFirst()
            .orElseThrow(() -> new AssertionError("No routing context for assessRisk capability"));

    assertThat(ctx.experiences())
        .as("Lambda feature extractor must produce matching experiences")
        .isNotEmpty();

    RetrievedExperience exp = ctx.experiences().get(0);
    assertThat(exp.problem()).isEqualTo("High-risk transaction detected");
    assertThat(exp.solution()).isEqualTo("Flag for manual review");
    assertThat(exp.outcome()).isEqualTo("COMPLETED");
    assertThat(exp.confidence()).isEqualTo(0.88);
    assertThat(exp.planTrace()).hasSize(1);
    assertThat(exp.planTrace().get(0).bindingName()).isEqualTo("analyse-risk");
  }

  // ------------------------------------------------------------------ //
  // Java DSL CaseHub bean with lambda feature extractor                  //
  // ------------------------------------------------------------------ //

  @ApplicationScoped
  public static class FuncDslCbrCaseHub extends CaseHub {

    @Override
    public CaseDefinition getDefinition() {
      Capability capability =
          Capability.builder()
              .name("assessRisk")
              .inputSchema("{ riskLevel: .transaction.riskLevel }")
              .outputSchema("{ assessment: .assessment }")
              .build();

      Worker worker =
          Worker.builder()
              .name("risk-assessor")
              .capabilityName("assessRisk")
              .function(
                  new WorkerFunction.Sync<>(
                      Map.class,
                      Map.class,
                      (input, scope) -> WorkerResult.of(Map.of("assessment", "reviewed"))))
              .build();

      CbrConfig cbrConfig =
          CbrConfig.builder()
              .featureExtractor(
                  ctx -> {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> txn =
                        (Map<String, Object>) ctx.layer(ContextLayer.WORKING).get("transaction");
                    if (txn == null) {
                      return Map.of();
                    }
                    Object riskLevel = txn.get("riskLevel");
                    Object amount = txn.get("amount");
                    if (riskLevel == null) {
                      return Map.of();
                    }
                    if (amount != null) {
                      return Map.of("riskLevel", riskLevel, "amount", amount);
                    }
                    return Map.of("riskLevel", riskLevel);
                  })
              .domain("func-dsl-domain")
              .caseType("risk-assessment")
              .topK(5)
              .minSimilarity(0.0)
              .build();

      return CaseDefinition.builder()
          .namespace("test")
          .name("Func DSL CBR Test")
          .version("1.0.0")
          .capabilities(capability)
          .workers(worker)
          .bindings(
              Binding.builder()
                  .name("analyse-risk")
                  .capability(capability)
                  .on(new ContextChangeTrigger(".transaction.riskLevel != null"))
                  .build())
          .cbrConfig(cbrConfig)
          .agentRouting("cbr-recording")
          .build();
    }
  }
}
