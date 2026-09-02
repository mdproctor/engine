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
import io.casehub.engine.internal.routing.GoalFormationEvaluator;
import io.casehub.neocortex.memory.CaseMemoryStore;
import io.casehub.neocortex.memory.MemoryDomain;
import io.casehub.neocortex.memory.MemoryInput;
import io.casehub.neocortex.memory.experience.ExperienceRecorder;
import io.casehub.neocortex.memory.experience.Outcome;
import io.casehub.neocortex.memory.reflection.ReflectionOrchestrator;
import io.casehub.worker.api.WorkerOutcome;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.jboss.logging.Logger;

@ApplicationScoped
public class AgentExperienceRecorder {

  private static final Logger LOG = Logger.getLogger(AgentExperienceRecorder.class);

  private static final MemoryDomain WORKER_REASONING_DOMAIN = new MemoryDomain("worker-reasoning");
  private static final int DEFAULT_MAX_REASONING_LENGTH = 4096;
  private static final String TRUNCATION_MARKER = "\n[...truncated...]\n";

  private final Instance<ExperienceRecorder> experienceRecorder;
  private final Instance<ReflectionOrchestrator> reflectionOrchestrator;
  private final CaseDefinitionRegistry caseDefinitionRegistry;
  private final GoalFormationEvaluator goalFormationEvaluator;
  private final Instance<CaseMemoryStore> caseMemoryStore;
  private final Instance<MeterRegistry> meterRegistry;
  private final ConcurrentHashMap<String, ReflectionState> reflectionStates =
      new ConcurrentHashMap<>();

  @org.eclipse.microprofile.config.inject.ConfigProperty(
      name = "casehub.reasoning.enabled",
      defaultValue = "true")
  boolean reasoningEnabled;

  @Inject
  public AgentExperienceRecorder(
      Instance<ExperienceRecorder> experienceRecorder,
      Instance<ReflectionOrchestrator> reflectionOrchestrator,
      CaseDefinitionRegistry caseDefinitionRegistry,
      GoalFormationEvaluator goalFormationEvaluator,
      Instance<CaseMemoryStore> caseMemoryStore,
      Instance<MeterRegistry> meterRegistry) {
    this.experienceRecorder = experienceRecorder;
    this.reflectionOrchestrator = reflectionOrchestrator;
    this.caseDefinitionRegistry = caseDefinitionRegistry;
    this.goalFormationEvaluator = goalFormationEvaluator;
    this.caseMemoryStore = caseMemoryStore;
    this.meterRegistry = meterRegistry;
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
              Instant.now(),
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

  public void storeReasoning(
      CaseInstance caseInstance,
      String workerName,
      String capabilityName,
      WorkerOutcome<?> outcome,
      String reasoning,
      String bindingName) {

    if (!reasoningEnabled
        || !caseMemoryStore.isResolvable()
        || reasoning == null
        || reasoning.isBlank()) {
      return;
    }

    String truncated = truncateReasoning(reasoning);
    String outcomeKind = outcomeKindName(outcome);
    HashMap<String, String> attributes = new HashMap<>();
    attributes.put("workerName", workerName);
    if (capabilityName != null) {
      attributes.put("capability", capabilityName);
    }
    attributes.put("bindingName", bindingName);
    attributes.put("outcome", outcomeKind);
    if (truncated.length() < reasoning.length()) {
      attributes.put("truncated", "true");
    }

    ReflectionTriggerConfig config = lookupConfig(caseInstance);
    double importance = resolveImportance(outcome, config);

    MemoryInput input =
        new MemoryInput(
            "case:" + caseInstance.getUuid(),
            WORKER_REASONING_DOMAIN,
            caseInstance.tenancyId,
            caseInstance.getUuid().toString(),
            truncated,
            Map.copyOf(attributes),
            io.casehub.neocortex.cognitive.Confidence.inferred(importance, java.time.Instant.now()),
            null,
            null,
            null);

    CaseMemoryStore store = caseMemoryStore.get();
    Thread.startVirtualThread(
        () -> {
          try {
            store.store(input);
          } catch (Exception e) {
            LOG.warnf(
                e,
                "Reasoning trace storage failed for case=%s worker=%s — non-critical",
                caseInstance.getUuid(),
                workerName);
            if (meterRegistry.isResolvable()) {
              Counter.builder("casehub.reasoning.storage.failures")
                  .tag("worker", workerName)
                  .register(meterRegistry.get())
                  .increment();
            }
          }
        });
  }

  String truncateReasoning(String reasoning) {
    if (reasoning.length() <= DEFAULT_MAX_REASONING_LENGTH) {
      return reasoning;
    }
    int headLen = DEFAULT_MAX_REASONING_LENGTH / 3;
    if (headLen > 0 && Character.isHighSurrogate(reasoning.charAt(headLen - 1))) {
      headLen--;
    }
    int tailLen = DEFAULT_MAX_REASONING_LENGTH - headLen - TRUNCATION_MARKER.length();
    int tailStart = reasoning.length() - tailLen;
    if (tailStart < reasoning.length() && Character.isLowSurrogate(reasoning.charAt(tailStart))) {
      tailStart++;
    }
    return reasoning.substring(0, headLen) + TRUNCATION_MARKER + reasoning.substring(tailStart);
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
              List<String> insights =
                  reflectionOrchestrator
                      .get()
                      .reflect(
                          workerName,
                          caseInstance.tenancyId,
                          sinceFinal,
                          config.maxSourceMemories());
              goalFormationEvaluator.evaluate(workerName, caseInstance, insights);
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

  static String outcomeKindName(WorkerOutcome<?> outcome) {
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
