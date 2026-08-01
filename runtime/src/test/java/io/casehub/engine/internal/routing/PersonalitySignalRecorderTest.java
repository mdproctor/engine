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

import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.casehub.api.model.CognitiveDemand;
import io.casehub.eidos.api.DispositionSignalStore;
import io.casehub.eidos.api.DispositionValue;
import jakarta.enterprise.inject.Instance;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class PersonalitySignalRecorderTest {

  private DispositionSignalStore signalStore;
  private PersonalitySignalRecorder recorder;

  @SuppressWarnings("unchecked")
  @BeforeEach
  void setUp() {
    signalStore = mock(DispositionSignalStore.class);
    Instance<DispositionSignalStore> storeInstance = mock(Instance.class);
    when(storeInstance.get()).thenReturn(signalStore);
    when(storeInstance.isResolvable()).thenReturn(true);
    recorder = new PersonalitySignalRecorder(storeInstance, null, null, null);
  }

  @Test
  void reinforcement_dominantMatchesDemand_recordsDominant() {
    var profile = List.of(new DispositionValue("Ti", 0.5), new DispositionValue("Ne", 0.3));
    var demand = new CognitiveDemand(Map.of("Ti", 0.6, "Ne", 0.3, "Si", 0.1));

    recorder.recordReinforcement("agent-1", "tenant-1", profile, demand);

    verify(signalStore).recordActivation("agent-1", "tenant-1", "Ti");
  }

  @Test
  void reinforcement_auxiliaryMatchesDemand_recordsAuxiliary() {
    var profile = List.of(new DispositionValue("Ti", 0.5), new DispositionValue("Ne", 0.3));
    var demand = new CognitiveDemand(Map.of("Ne", 0.7, "Ti", 0.2, "Si", 0.1));

    recorder.recordReinforcement("agent-1", "tenant-1", profile, demand);

    verify(signalStore).recordActivation("agent-1", "tenant-1", "Ne");
  }

  @Test
  void compensation_recordsHighestDemandOutsideDomAux() {
    var profile = List.of(new DispositionValue("Ti", 0.5), new DispositionValue("Ne", 0.3));
    var demand = new CognitiveDemand(Map.of("Se", 0.5, "Ti", 0.3, "Ne", 0.2));

    recorder.recordCompensation("agent-1", "tenant-1", profile, demand);

    verify(signalStore).recordActivation("agent-1", "tenant-1", "Se");
  }

  @Test
  void compensation_allDemandOnDomAux_skipsRecording() {
    var profile = List.of(new DispositionValue("Ti", 0.5), new DispositionValue("Ne", 0.3));
    var demand = new CognitiveDemand(Map.of("Ti", 0.6, "Ne", 0.4));

    recorder.recordCompensation("agent-1", "tenant-1", profile, demand);

    verifyNoInteractions(signalStore);
  }

  @Test
  void reinforcement_singleFunctionProfile_recordsDominant() {
    var profile = List.of(new DispositionValue("Ti", 0.8));
    var demand = new CognitiveDemand(Map.of("Ti", 0.5, "Ne", 0.3, "Si", 0.2));

    recorder.recordReinforcement("agent-1", "tenant-1", profile, demand);

    verify(signalStore).recordActivation("agent-1", "tenant-1", "Ti");
  }

  @Test
  void checkReflection_evolutionPending_dampened_callsDecay() {
    var dispositionHealth = mock(io.casehub.eidos.api.DispositionHealth.class);
    var dispositionEvolution = mock(io.casehub.eidos.api.DispositionEvolution.class);
    @SuppressWarnings("unchecked")
    Instance<io.casehub.eidos.api.DispositionHealth> healthInst = mock(Instance.class);
    when(healthInst.get()).thenReturn(dispositionHealth);
    when(healthInst.isResolvable()).thenReturn(true);
    @SuppressWarnings("unchecked")
    Instance<io.casehub.eidos.api.DispositionEvolution> evolInst = mock(Instance.class);
    when(evolInst.get()).thenReturn(dispositionEvolution);
    when(evolInst.isResolvable()).thenReturn(true);
    @SuppressWarnings("unchecked")
    Instance<io.casehub.eidos.api.DispositionSignalStore> storeInst = mock(Instance.class);
    when(storeInst.get()).thenReturn(signalStore);
    when(storeInst.isResolvable()).thenReturn(true);
    var recorderWithReflection =
        new PersonalitySignalRecorder(storeInst, null, healthInst, evolInst);

    var descriptor =
        io.casehub.eidos.api.AgentDescriptor.builder()
            .agentId("agent-1")
            .name("agent-1")
            .slot("test")
            .tenancyId("t1")
            .build();
    var pending =
        new io.casehub.eidos.api.DispositionHealth.DispositionStatus.EvolutionPending(
            new io.casehub.eidos.api.EvolutionType() {
              @Override
              public String name() {
                return "ROLE_SWAP";
              }
            },
            "Ne",
            java.util.Map.of("Ti", 0.4, "Ne", 0.35));
    when(dispositionHealth.probe(any(), any())).thenReturn(pending);
    when(dispositionEvolution.evaluate(any(), any()))
        .thenReturn(new io.casehub.eidos.api.DispositionEvolution.EvolutionResult.Dampened(0.2));

    recorderWithReflection.checkReflection("agent-1", "tenant-1", descriptor);

    verify(signalStore).decay("agent-1", "tenant-1", 0.2);
  }

  @Test
  void checkReflection_evolutionPending_evolved_callsClear() {
    var dispositionHealth = mock(io.casehub.eidos.api.DispositionHealth.class);
    var dispositionEvolution = mock(io.casehub.eidos.api.DispositionEvolution.class);
    @SuppressWarnings("unchecked")
    Instance<io.casehub.eidos.api.DispositionHealth> healthInst = mock(Instance.class);
    when(healthInst.get()).thenReturn(dispositionHealth);
    when(healthInst.isResolvable()).thenReturn(true);
    @SuppressWarnings("unchecked")
    Instance<io.casehub.eidos.api.DispositionEvolution> evolInst = mock(Instance.class);
    when(evolInst.get()).thenReturn(dispositionEvolution);
    when(evolInst.isResolvable()).thenReturn(true);
    @SuppressWarnings("unchecked")
    Instance<io.casehub.eidos.api.DispositionSignalStore> storeInst = mock(Instance.class);
    when(storeInst.get()).thenReturn(signalStore);
    when(storeInst.isResolvable()).thenReturn(true);
    var recorderWithReflection =
        new PersonalitySignalRecorder(storeInst, null, healthInst, evolInst);

    var descriptor =
        io.casehub.eidos.api.AgentDescriptor.builder()
            .agentId("agent-1")
            .name("agent-1")
            .slot("test")
            .tenancyId("t1")
            .build();
    var pending =
        new io.casehub.eidos.api.DispositionHealth.DispositionStatus.EvolutionPending(
            new io.casehub.eidos.api.EvolutionType() {
              @Override
              public String name() {
                return "ROLE_SWAP";
              }
            },
            "Ne",
            java.util.Map.of("Ti", 0.4, "Ne", 0.35));
    when(dispositionHealth.probe(any(), any())).thenReturn(pending);
    when(dispositionEvolution.evaluate(any(), any()))
        .thenReturn(
            new io.casehub.eidos.api.DispositionEvolution.EvolutionResult.Evolved(
                java.util.List.of(
                    new io.casehub.eidos.api.DispositionValue("Ne", 0.35),
                    new io.casehub.eidos.api.DispositionValue("Ti", 0.20)),
                "TI-NE",
                "NE-TI"));

    recorderWithReflection.checkReflection("agent-1", "tenant-1", descriptor);

    verify(signalStore).clear("agent-1", "tenant-1");
    verify(signalStore, never()).decay(any(), any(), anyDouble());
  }

  @Test
  void checkReflection_aligned_noReflection() {
    var dispositionHealth = mock(io.casehub.eidos.api.DispositionHealth.class);
    @SuppressWarnings("unchecked")
    Instance<io.casehub.eidos.api.DispositionHealth> healthInst2 = mock(Instance.class);
    when(healthInst2.get()).thenReturn(dispositionHealth);
    when(healthInst2.isResolvable()).thenReturn(true);
    @SuppressWarnings("unchecked")
    Instance<io.casehub.eidos.api.DispositionSignalStore> storeInst2 = mock(Instance.class);
    when(storeInst2.get()).thenReturn(signalStore);
    when(storeInst2.isResolvable()).thenReturn(true);
    var recorderWithReflection = new PersonalitySignalRecorder(storeInst2, null, healthInst2, null);

    var descriptor =
        io.casehub.eidos.api.AgentDescriptor.builder()
            .agentId("agent-1")
            .name("agent-1")
            .slot("test")
            .tenancyId("t1")
            .build();
    when(dispositionHealth.probe(any(), any()))
        .thenReturn(
            new io.casehub.eidos.api.DispositionHealth.DispositionStatus.Aligned(
                java.util.Map.of()));

    recorderWithReflection.checkReflection("agent-1", "tenant-1", descriptor);

    verifyNoInteractions(signalStore);
  }
}
