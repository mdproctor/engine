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

import io.casehub.api.engine.YamlCaseHub;
import io.casehub.api.model.CaseDefinition;
import io.casehub.api.spi.routing.AgentRoutingContext;
import io.casehub.api.spi.routing.RetrievedExperience;
import io.casehub.api.spi.routing.RoutingOutcome;
import io.casehub.neocortex.memory.MemoryDomain;
import io.casehub.neocortex.memory.cbr.CbrCaseMemoryStore;
import io.casehub.neocortex.memory.cbr.FeatureValue;
import io.casehub.neocortex.memory.cbr.PlanCbrCase;
import io.casehub.neocortex.memory.cbr.PlanTrace;
import io.casehub.platform.api.identity.TenancyConstants;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
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
 * End-to-end integration test verifying the YAML-path CBR retrieval flow: CONTEXT_CHANGED fires,
 * CbrRetrievalService retrieves similar experiences from InMemoryCbrCaseMemoryStore, and the
 * recording AgentRoutingStrategy receives them in the AgentRoutingContext.
 *
 * <p>Refs casehubio/engine#478.
 */
@QuarkusTest
@TestProfile(CbrRoutingIntegrationTest.MemoryProfile.class)
class CbrRoutingIntegrationTest {

  @Inject CbrYamlCaseHub caseHub;
  @Inject CbrCaseMemoryStore cbrStore;

  @BeforeEach
  void reset() {
    RecordingCbrAgentRoutingStrategy.reset();
  }

  @Test
  void yamlCbrConfig_experiencesReachAgentRoutingStrategy() {
    // Pre-load the CBR store with a matching case
    PlanTrace trace =
        new PlanTrace(
            "plan-on-enemy-sighted", "planBattle", "battle-planner", "SUCCESS", 0, Map.of());
    PlanCbrCase pastCase =
        new PlanCbrCase(
            "Enemy aggressive with 100 troops",
            "Flank from the east",
            "COMPLETED",
            0.92,
            Map.of(
                "posture", FeatureValue.string("aggressive"), "armySize", FeatureValue.number(100)),
            List.of(trace),
            null,
            null);
    cbrStore.store(
        pastCase,
        "battle",
        "entity-1",
        new MemoryDomain("cbr-test-domain"),
        TenancyConstants.DEFAULT_TENANT_ID,
        UUID.randomUUID().toString(),
        io.casehub.platform.api.path.Path.root());

    // Start the case with context that triggers the binding
    caseHub.startCase(Map.of("enemy", Map.of("posture", "aggressive", "army_size", 80)));

    // Wait for the recording strategy to be invoked with a planBattle context
    await()
        .atMost(15, TimeUnit.SECONDS)
        .untilAsserted(
            () ->
                assertThat(RecordingCbrAgentRoutingStrategy.capturedContexts)
                    .as("AgentRoutingStrategy must be invoked with CBR experiences")
                    .anyMatch(c -> "planBattle".equals(c.capabilityName())));

    // Verify the captured experiences for the planBattle capability
    AgentRoutingContext ctx =
        RecordingCbrAgentRoutingStrategy.capturedContexts.stream()
            .filter(c -> "planBattle".equals(c.capabilityName()))
            .findFirst()
            .orElseThrow(() -> new AssertionError("No routing context for planBattle capability"));

    assertThat(ctx.experiences())
        .as("Experiences list must not be empty when CBR is configured and store has matches")
        .isNotEmpty();

    RetrievedExperience exp = ctx.experiences().get(0);
    assertThat(exp.problem()).isEqualTo("Enemy aggressive with 100 troops");
    assertThat(exp.solution()).isEqualTo("Flank from the east");
    assertThat(exp.outcome()).isEqualTo("COMPLETED");
    assertThat(exp.confidence()).isEqualTo(0.92);
    assertThat(exp.similarityScore()).isGreaterThan(0.0);
    assertThat(exp.features()).containsEntry("posture", FeatureValue.string("aggressive"));
    assertThat(exp.planTrace()).hasSize(1);
    assertThat(exp.planTrace().get(0).bindingName()).isEqualTo("plan-on-enemy-sighted");
    assertThat(exp.planTrace().get(0).capabilityName()).isEqualTo("planBattle");
    assertThat(exp.planTrace().get(0).workerName()).isEqualTo("battle-planner");
    assertThat(exp.planTrace().get(0).stepOutcome()).isEqualTo(RoutingOutcome.SUCCESS);
  }

  // ------------------------------------------------------------------ //
  // YAML CaseHub bean                                                    //
  // ------------------------------------------------------------------ //

  @ApplicationScoped
  public static class CbrYamlCaseHub extends YamlCaseHub {

    public CbrYamlCaseHub() {
      super("casehub/cbr-routing-test.yaml");
    }

    @Override
    protected void augment(CaseDefinition definition) {
      definition.setAgentRouting("cbr-recording");
    }
  }

  public static class MemoryProfile implements QuarkusTestProfile {
    @Override
    public String getConfigProfile() {
      return "memory";
    }
  }
}
