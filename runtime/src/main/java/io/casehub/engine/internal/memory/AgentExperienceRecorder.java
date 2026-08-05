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
package io.casehub.engine.internal.memory;

import io.casehub.api.model.ReflectionTriggerConfig;
import io.casehub.engine.common.internal.model.CaseInstance;
import io.casehub.engine.common.spi.CaseDefinitionRegistry;
import io.casehub.neocortex.memory.experience.ExperienceRecorder;
import io.casehub.neocortex.memory.experience.Outcome;
import io.casehub.neocortex.memory.reflection.ReflectionOrchestrator;
import io.casehub.worker.api.WorkerOutcome;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.jboss.logging.Logger;

@ApplicationScoped
public class AgentExperienceRecorder {

  private static final Logger LOG = Logger.getLogger(AgentExperienceRecorder.class);

  private final Instance<ExperienceRecorder> experienceRecorder;
  private final Instance<ReflectionOrchestrator> reflectionOrchestrator;
  private final CaseDefinitionRegistry caseDefinitionRegistry;
  private final ConcurrentHashMap<String, ReflectionState> reflectionStates =
      new ConcurrentHashMap<>();

  @Inject
  public AgentExperienceRecorder(
      Instance<ExperienceRecorder> experienceRecorder,
      Instance<ReflectionOrchestrator> reflectionOrchestrator,
      CaseDefinitionRegistry caseDefinitionRegistry) {
    this.experienceRecorder = experienceRecorder;
    this.reflectionOrchestrator = reflectionOrchestrator;
    this.caseDefinitionRegistry = caseDefinitionRegistry;
  }

  public void record(
      CaseInstance caseInstance,
      String workerName,
      String capabilityName,
      WorkerOutcome<?> outcome,
      String bindingName) {
    if (!experienceRecorder.isResolvable()) return;

    ReflectionTriggerConfig config = lookupConfig(caseInstance);
    double importance = resolveImportance(outcome, config);

    try {
      var event =
          new Outcome(
              workerName,
              caseInstance.tenancyId,
              caseInstance.getUuid().toString(),
              UUID.randomUUID().toString(),
              buildDescription(capabilityName, outcome),
              importance,
              Map.of(
                  "bindingName",
                  bindingName,
                  "caseDefinitionName",
                  caseInstance.getCaseMetaModel().getName()),
              outcomeKindName(outcome),
              capabilityName);
      experienceRecorder.get().record(event);
    } catch (Exception e) {
      LOG.warnf(e, "Failed to record experience for agent %s", workerName);
    }

    evaluateReflectionTrigger(caseInstance, workerName, importance, config);
  }

  private ReflectionTriggerConfig lookupConfig(CaseInstance caseInstance) {
    try {
      var def = caseDefinitionRegistry.getCaseDefinition(caseInstance.getCaseMetaModel());
      return def.getReflectionTrigger();
    } catch (Exception e) {
      return null;
    }
  }

  private double resolveImportance(WorkerOutcome<?> outcome, ReflectionTriggerConfig config) {
    Map<String, Double> weights =
        config != null && !config.importanceWeights().isEmpty()
            ? config.importanceWeights()
            : ReflectionTriggerConfig.DEFAULT_IMPORTANCE_WEIGHTS;
    String kind = outcomeKindName(outcome);
    return weights.getOrDefault(kind, 0.3);
  }

  private void evaluateReflectionTrigger(
      CaseInstance caseInstance,
      String workerName,
      double importance,
      ReflectionTriggerConfig config) {
    if (config == null || !config.enabled() || !reflectionOrchestrator.isResolvable()) return;

    String key = workerName + "|" + caseInstance.tenancyId;
    var shouldReflect = new boolean[] {false};
    var since = new Instant[] {null};

    reflectionStates.compute(
        key,
        (k, state) -> {
          if (state == null) state = new ReflectionState();
          state.outcomeCount++;
          state.cumulativeImportance += importance;
          if (state.outcomeCount >= config.maxUnreflectedOutcomes()
              || state.cumulativeImportance >= config.importanceThreshold()) {
            since[0] = state.lastReflectionTime;
            state.outcomeCount = 0;
            state.cumulativeImportance = 0.0;
            state.lastReflectionTime = Instant.now();
            shouldReflect[0] = true;
          }
          return state;
        });

    if (shouldReflect[0]) {
      final Instant sinceFinal = since[0];
      Thread.startVirtualThread(
          () -> {
            try {
              reflectionOrchestrator
                  .get()
                  .reflect(
                      workerName, caseInstance.tenancyId, sinceFinal, config.maxSourceMemories());
            } catch (Exception e) {
              LOG.warnf(e, "Reflection failed for agent %s", workerName);
            }
          });
    }
  }

  private static String buildDescription(String capabilityName, WorkerOutcome<?> outcome) {
    return switch (outcome) {
      case WorkerOutcome.Success<?> s -> "Completed " + capabilityName;
      case WorkerOutcome.Completed<?> c -> "Completed " + capabilityName;
      case WorkerOutcome.Declined<?> d -> "Declined " + capabilityName + ": " + d.reason();
      case WorkerOutcome.Failed<?> f -> "Failed " + capabilityName + ": " + f.reason();
      case WorkerOutcome.Expired<?> e -> "Expired " + capabilityName + ": " + e.reason();
    };
  }

  private static String outcomeKindName(WorkerOutcome<?> outcome) {
    return switch (outcome) {
      case WorkerOutcome.Success<?> s -> "SUCCESS";
      case WorkerOutcome.Completed<?> c -> "COMPLETED";
      case WorkerOutcome.Declined<?> d -> "DECLINED";
      case WorkerOutcome.Failed<?> f -> "FAILED";
      case WorkerOutcome.Expired<?> e -> "EXPIRED";
    };
  }

  private static class ReflectionState {
    int outcomeCount;
    double cumulativeImportance;
    Instant lastReflectionTime;
  }
}
