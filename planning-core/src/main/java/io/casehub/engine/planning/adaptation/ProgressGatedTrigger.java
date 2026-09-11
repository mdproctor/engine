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
package io.casehub.engine.planning.adaptation;

import com.fasterxml.jackson.databind.JsonNode;
import io.casehub.api.model.AdaptationConfig;
import io.casehub.api.model.Binding;
import io.casehub.api.model.CaseDefinition;
import io.casehub.api.model.ReplanHint;
import io.casehub.api.model.TaskStatus;
import io.casehub.api.model.event.CaseHubEventType;
import io.casehub.engine.common.internal.history.EventLog;
import io.casehub.engine.common.internal.monitoring.DivergenceScoreComputer;
import io.casehub.engine.common.spi.EventLogRepository;
import io.casehub.engine.plan.adaptation.AdaptationContext;
import io.casehub.engine.plan.adaptation.AdaptationSignal;
import io.casehub.engine.plan.adaptation.AdaptationTrigger;
import io.casehub.engine.plan.monitoring.MonitoringConfig;
import java.util.List;

public class ProgressGatedTrigger implements AdaptationTrigger {

  private final EventLogRepository eventLogRepository;

  public ProgressGatedTrigger(EventLogRepository eventLogRepository) {
    this.eventLogRepository = eventLogRepository;
  }

  @Override
  public AdaptationSignal evaluate(AdaptationContext context) {
    CaseDefinition definition = context.definition();

    ReplanHint hint = resolveHint(definition, context.latestBindingName());
    if (hint == ReplanHint.ALWAYS) {
      return AdaptationSignal.PROCEED;
    }
    if (hint == ReplanHint.NEVER) {
      return AdaptationSignal.SKIP;
    }

    TaskStatus status = context.latestStatus();
    if (status != TaskStatus.COMPLETED && status.isTerminal()) {
      return AdaptationSignal.PROCEED;
    }

    MonitoringConfig monitoring = definition.getMonitoringConfig();
    if (monitoring == null || !monitoring.enabled()) {
      return AdaptationSignal.SKIP;
    }

    double threshold = resolveThreshold(definition);

    List<EventLog> completions =
        eventLogRepository.findByCaseAndTypes(
            context.caseId(),
            List.of(CaseHubEventType.WORKER_EXECUTION_COMPLETED),
            context.tenancyId());

    List<EventLog> compoundCompletions =
        completions.stream().filter(e -> matchesCompound(e, context.compoundId())).toList();

    double score =
        DivergenceScoreComputer.computeForCompound(
            compoundCompletions, monitoring.windowSize(), context.adaptationGeneration());

    return score > threshold ? AdaptationSignal.PROCEED : AdaptationSignal.SKIP;
  }

  @Override
  public String id() {
    return "progress";
  }

  private ReplanHint resolveHint(CaseDefinition definition, String bindingName) {
    if (definition.getBindings() == null || bindingName == null) {
      return ReplanHint.CONDITIONAL;
    }
    for (Binding binding : definition.getBindings()) {
      if (bindingName.equals(binding.getName())) {
        return binding.getReplanHint();
      }
    }
    return ReplanHint.CONDITIONAL;
  }

  private double resolveThreshold(CaseDefinition definition) {
    AdaptationConfig config = definition.getAdaptationConfig();
    if (config != null && config.threshold() != null) {
      return config.threshold();
    }
    return AdaptationConfig.DEFAULT_PROGRESS_THRESHOLD;
  }

  private boolean matchesCompound(EventLog entry, String compoundId) {
    JsonNode meta = entry.getMetadata();
    if (meta == null || !meta.has("expectationValidation")) {
      return false;
    }
    JsonNode validation = meta.get("expectationValidation");
    if (!validation.has("compoundId")) {
      return compoundId == null;
    }
    return compoundId != null && compoundId.equals(validation.get("compoundId").asText());
  }
}
