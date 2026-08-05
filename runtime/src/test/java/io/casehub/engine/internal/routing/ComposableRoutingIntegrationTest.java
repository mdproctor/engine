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

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import io.casehub.api.model.CognitiveDemand;
import io.casehub.api.spi.routing.AgentCandidate;
import io.casehub.api.spi.routing.AgentHealth;
import io.casehub.api.spi.routing.AgentRoutingContext;
import io.casehub.api.spi.routing.RoutingResult;
import io.casehub.api.spi.routing.RoutingSignalAssembler;
import io.casehub.api.spi.routing.RoutingSignalProvider;
import io.casehub.eidos.api.AgentDescriptor;
import io.casehub.eidos.api.AgentDisposition;
import io.casehub.eidos.api.DispositionHealth;
import io.casehub.eidos.api.DispositionValue;
import jakarta.enterprise.inject.Instance;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * End-to-end demonstration of the composable routing architecture with personality-adaptive agent
 * selection.
 *
 * <p>Scenario: An AML investigation case needs an "entity-resolution" task completed. The task
 * demands strong introverted thinking (Ti) for systematic analysis. Three agents are available:
 *
 * <ul>
 *   <li><b>analyst-alpha</b> — Ti-dominant (analytical thinker), low workload → best personality
 *       match
 *   <li><b>investigator-beta</b> — Ni-dominant (pattern recognizer), medium workload → moderate
 *       match
 *   <li><b>resolver-gamma</b> — Fe-dominant (empathetic communicator), high workload → poor match
 * </ul>
 *
 * <p>The compositor blends personality alignment (60%) with workload (40%). Without personality
 * routing, the least-loaded agent wins. With it, the analytically-suited agent is selected despite
 * not being the least loaded.
 */
class ComposableRoutingIntegrationTest {

  @Test
  void workloadOnly_selectsLeastLoaded() {
    var compositor = composable(new WorkloadSignalProvider());

    var result =
        compositor.select(
            context(null, null),
            List.of(
                agent("analyst-alpha", personality("Ti", 0.45, "Ne", 0.25), 2),
                agent("investigator-beta", personality("Ni", 0.40, "Te", 0.30), 1),
                agent("resolver-gamma", personality("Fe", 0.50, "Si", 0.20), 0)));

    assertSelected(result, "resolver-gamma");
  }

  @Test
  void personalityOnly_selectsBestAligned() {
    var demand = new CognitiveDemand(Map.of("Ti", 0.6, "Si", 0.25, "Ne", 0.15));
    var health = healthReturning();
    var compositor = composable(new PersonalitySignalProvider(health));

    var result =
        compositor.select(
            context(demand, null),
            List.of(
                agent("analyst-alpha", personality("Ti", 0.45, "Ne", 0.25), 2),
                agent("investigator-beta", personality("Ni", 0.40, "Te", 0.30), 1),
                agent("resolver-gamma", personality("Fe", 0.50, "Si", 0.20), 0)));

    assertSelected(result, "analyst-alpha");
  }

  @Test
  void blendedPersonalityAndWorkload_personalityWeightedHigher_selectsAlignedAgent() {
    var demand = new CognitiveDemand(Map.of("Ti", 0.6, "Si", 0.25, "Ne", 0.15));
    var health = healthReturning();
    var weights = Map.of("personality", 0.6, "workload", 0.4);
    var compositor =
        composable(new PersonalitySignalProvider(health), new WorkloadSignalProvider());

    var result =
        compositor.select(
            context(demand, weights),
            List.of(
                agent("analyst-alpha", personality("Ti", 0.45, "Ne", 0.25), 2),
                agent("investigator-beta", personality("Ni", 0.40, "Te", 0.30), 1),
                agent("resolver-gamma", personality("Fe", 0.50, "Si", 0.20), 0)));

    assertSelected(result, "analyst-alpha");
  }

  @Test
  void blendedPersonalityAndWorkload_workloadWeightedHigher_selectsLeastLoaded() {
    var demand = new CognitiveDemand(Map.of("Ti", 0.6, "Si", 0.25, "Ne", 0.15));
    var health = healthReturning();
    var weights = Map.of("personality", 0.2, "workload", 0.8);
    var compositor =
        composable(new PersonalitySignalProvider(health), new WorkloadSignalProvider());

    var result =
        compositor.select(
            context(demand, weights),
            List.of(
                agent("analyst-alpha", personality("Ti", 0.45, "Ne", 0.25), 2),
                agent("investigator-beta", personality("Ni", 0.40, "Te", 0.30), 1),
                agent("resolver-gamma", personality("Fe", 0.50, "Si", 0.20), 0)));

    assertSelected(result, "resolver-gamma");
  }

  @Test
  void agentWithoutPersonalityProfile_gracefullyExcludedFromPersonalityScoring() {
    var demand = new CognitiveDemand(Map.of("Ti", 0.6, "Si", 0.25, "Ne", 0.15));
    var health = healthReturning();
    var weights = Map.of("personality", 0.5, "workload", 0.5);
    var compositor =
        composable(new PersonalitySignalProvider(health), new WorkloadSignalProvider());

    var noProfileAgent =
        new AgentCandidate("plain-agent", Set.of(), 0, AgentHealth.READY, null, null, null);

    var result =
        compositor.select(
            context(demand, weights),
            List.of(
                agent("analyst-alpha", personality("Ti", 0.45, "Ne", 0.25), 2), noProfileAgent));

    assertThat(result).isInstanceOf(RoutingResult.Selected.class);
  }

  @Test
  void noCognitiveDemand_personalityProviderAbstains_fallsBackToWorkload() {
    var health = healthReturning();
    var weights = Map.of("personality", 0.6, "workload", 0.4);
    var compositor =
        composable(new PersonalitySignalProvider(health), new WorkloadSignalProvider());

    var result =
        compositor.select(
            context(null, weights),
            List.of(
                agent("analyst-alpha", personality("Ti", 0.45, "Ne", 0.25), 5),
                agent("resolver-gamma", personality("Fe", 0.50, "Si", 0.20), 0)));

    assertSelected(result, "resolver-gamma");
  }

  // --- helpers ---

  private static ComposableAgentRoutingStrategy composable(RoutingSignalProvider... providers) {
    return new ComposableAgentRoutingStrategy(new RoutingSignalAssembler(List.of(providers)));
  }

  private static AgentDisposition personality(String dom, double domW, String aux, double auxW) {
    return AgentDisposition.builder()
        .dispositionProfile(new DispositionValue(dom, domW), new DispositionValue(aux, auxW))
        .build();
  }

  private static AgentCandidate agent(String id, AgentDisposition disposition, int runningJobs) {
    var descriptor =
        AgentDescriptor.builder()
            .agentId(id)
            .name(id)
            .slot("aml")
            .tenancyId("tenant-aml")
            .disposition(disposition)
            .build();
    return new AgentCandidate(
        id, Set.of("entity-resolution"), runningJobs, AgentHealth.READY, descriptor, null, null);
  }

  @SuppressWarnings("unchecked")
  private static Instance<DispositionHealth> healthReturning() {
    DispositionHealth health =
        (descriptor, ctx) -> {
          var profile = descriptor.disposition().dispositionProfile();
          var weights = new java.util.LinkedHashMap<String, Double>();
          for (var dv : profile) {
            weights.put(dv.term(), dv.weight());
          }
          return new DispositionHealth.DispositionStatus.Aligned(weights);
        };
    Instance<DispositionHealth> inst = org.mockito.Mockito.mock(Instance.class);
    org.mockito.Mockito.when(inst.get()).thenReturn(health);
    org.mockito.Mockito.when(inst.isResolvable()).thenReturn(true);
    return inst;
  }

  private static AgentRoutingContext context(CognitiveDemand demand, Map<String, Double> weights) {
    return new AgentRoutingContext(
        UUID.randomUUID(),
        "entity-resolution",
        JsonNodeFactory.instance.objectNode().put("transaction", "TXN-001"),
        "tenant-aml",
        List.of(),
        demand,
        weights);
  }

  private static void assertSelected(RoutingResult result, String expectedWorkerId) {
    assertThat(result).isInstanceOf(RoutingResult.Selected.class);
    assertThat(((RoutingResult.Selected) result).single().executorId()).isEqualTo(expectedWorkerId);
  }
}
