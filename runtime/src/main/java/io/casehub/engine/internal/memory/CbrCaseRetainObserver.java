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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.casehub.api.model.Binding;
import io.casehub.api.model.CapabilityTarget;
import io.casehub.api.model.CaseDefinition;
import io.casehub.api.model.EpisodicMemoryConfig;
import io.casehub.api.model.TaskStatus;
import io.casehub.api.model.cbr.CbrConfig;
import io.casehub.api.model.cbr.JqFeatureExtractor;
import io.casehub.api.model.cbr.LambdaFeatureExtractor;
import io.casehub.api.spi.CaseOutcomeEvent;
import io.casehub.api.spi.CaseOutcomeObserver;
import io.casehub.api.spi.routing.RoutingOutcome;
import io.casehub.engine.common.internal.jq.JQEvaluator;
import io.casehub.engine.common.internal.jq.ValidationResult;
import io.casehub.engine.common.internal.model.PlanItemRecord;
import io.casehub.engine.common.spi.CaseDefinitionRegistry;
import io.casehub.engine.common.spi.PlanItemStore;
import io.casehub.ledger.api.spi.TrustScoreSource;
import io.casehub.neocortex.memory.MemoryDomain;
import io.casehub.neocortex.memory.cbr.CbrCaseMemoryStore;
import io.casehub.neocortex.memory.cbr.FeatureValue;
import io.casehub.neocortex.memory.cbr.PlanCbrCase;
import io.casehub.neocortex.memory.cbr.PlanTrace;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.jboss.logging.Logger;

@ApplicationScoped
public class CbrCaseRetainObserver implements CaseOutcomeObserver {

  private static final Logger LOG = Logger.getLogger(CbrCaseRetainObserver.class);
  private static final ObjectMapper MAPPER = new ObjectMapper();

  private static final Map<TaskStatus, RoutingOutcome> OUTCOME_MAP =
      Map.of(
          TaskStatus.COMPLETED, RoutingOutcome.SUCCESS,
          TaskStatus.FAULTED, RoutingOutcome.FAILURE,
          TaskStatus.REJECTED, RoutingOutcome.DECLINED,
          TaskStatus.CANCELLED, RoutingOutcome.CANCELLED,
          TaskStatus.OBSOLETE, RoutingOutcome.OBSOLETE);

  private final CbrCaseMemoryStore cbrStore;
  private final CaseDefinitionRegistry registry;
  private final Instance<PlanItemStore> planItemStoreInstance;
  private final JQEvaluator jqEvaluator;
  private final Instance<TrustScoreSource> trustScoreSource;

  @Inject
  public CbrCaseRetainObserver(
      CbrCaseMemoryStore cbrStore,
      CaseDefinitionRegistry registry,
      Instance<PlanItemStore> planItemStoreInstance,
      JQEvaluator jqEvaluator,
      Instance<TrustScoreSource> trustScoreSource) {
    this.cbrStore = cbrStore;
    this.registry = registry;
    this.planItemStoreInstance = planItemStoreInstance;
    this.jqEvaluator = jqEvaluator;
    this.trustScoreSource = trustScoreSource;
  }

  @Override
  public void onOutcome(CaseOutcomeEvent event) {
    try {
      doRetain(event);
    } catch (Exception e) {
      LOG.warnf(
          e,
          "CbrCaseRetainObserver failed for caseId=%s caseType='%s' — continuing",
          event.caseId(),
          event.caseType());
    }
  }

  private void doRetain(CaseOutcomeEvent event) {
    if (planItemStoreInstance.isUnsatisfied()) {
      return;
    }

    CaseDefinition definition;
    try {
      var opt = registry.findByName(event.caseType());
      if (opt.isEmpty()) {
        LOG.warnf(
            "CBR retain: definition not registered at case close for caseType='%s'",
            event.caseType());
        return;
      }
      definition = opt.get();
    } catch (IllegalArgumentException e) {
      LOG.warnf(
          "CBR retain: ambiguous definition name '%s' — %s", event.caseType(), e.getMessage());
      return;
    }

    CbrConfig config = definition.getCbrConfig();
    if (config == null) {
      return;
    }

    String domain = resolveDomain(config, definition);
    if (domain == null) {
      LOG.warnf(
          "CBR retain: domain unresolvable for case definition '%s' — skipping",
          definition.getName());
      return;
    }

    Map<String, FeatureValue> features = extractFeatures(config, event.caseFileSnapshot());
    if (features.isEmpty()) {
      LOG.warnf(
          "CBR retain: all features evaluated to empty for case definition '%s' — skipping",
          definition.getName());
      return;
    }

    Map<String, String> capabilityNameMap = buildRoutingKeyMap(definition);

    PlanItemStore planItemStore = planItemStoreInstance.get();
    List<PlanItemRecord> records = planItemStore.findByCaseId(event.caseId(), event.tenancyId());

    List<PlanItemRecord> sorted =
        records.stream()
            .filter(r -> r.status().isTerminal())
            .filter(r -> capabilityNameMap.containsKey(r.bindingName()))
            .filter(r -> r.executorName() != null)
            .sorted(Comparator.comparing(PlanItemRecord::createdAt))
            .toList();

    List<PlanTrace> traces = new ArrayList<>(sorted.size());
    for (int i = 0; i < sorted.size(); i++) {
      traces.add(toPlanTrace(sorted.get(i), capabilityNameMap, i));
    }

    if (traces.isEmpty()) {
      LOG.debugf(
          "CBR retain: no terminal capability plan items for caseId=%s — skipping", event.caseId());
      return;
    }

    String solution =
        traces.stream()
            .map(t -> t.bindingName() + "→" + t.workerName() + "(" + t.stepOutcome() + ")")
            .collect(Collectors.joining(", "));

    String producerAgentId = deriveProducerAgentId(traces);
    Double trustScore = lookupTrustScore(producerAgentId);

    PlanCbrCase cbrCase =
        new PlanCbrCase(
            event.caseType(),
            solution,
            event.outcomeLabel(),
            null,
            features,
            traces,
            trustScore,
            producerAgentId);

    cbrStore.store(
        cbrCase,
        event.caseType(),
        "case-retain",
        new MemoryDomain(domain),
        event.tenancyId(),
        event.caseId().toString(),
        io.casehub.platform.api.path.Path.root());
  }

  private String deriveProducerAgentId(List<PlanTrace> traces) {
    return traces.stream()
        .filter(t -> "SUCCESS".equals(t.stepOutcome()))
        .map(PlanTrace::workerName)
        .findFirst()
        .orElseGet(() -> traces.get(0).workerName());
  }

  private Double lookupTrustScore(String agentId) {
    if (agentId == null || trustScoreSource.isUnsatisfied()) {
      return null;
    }
    try {
      var score = trustScoreSource.get().globalScore(agentId);
      return score.isPresent() ? score.getAsDouble() : null;
    } catch (Exception e) {
      LOG.debugf("Trust score lookup failed for agent '%s' — continuing without", agentId);
      return null;
    }
  }

  private String resolveDomain(CbrConfig config, CaseDefinition definition) {
    if (config.domain() != null) {
      return config.domain();
    }
    EpisodicMemoryConfig episodic = definition.getEpisodicMemoryConfig();
    if (episodic != null) {
      return episodic.domain();
    }
    return null;
  }

  private Map<String, FeatureValue> extractFeatures(
      CbrConfig config, Map<String, Object> snapshot) {
    return switch (config.featureExtractor()) {
      case JqFeatureExtractor jq -> extractJqFeatures(jq, snapshot);
      case LambdaFeatureExtractor lambda ->
          FeatureValue.toFeatureMap(lambda.extract(new SnapshotCaseContext(snapshot)));
    };
  }

  private Map<String, FeatureValue> extractJqFeatures(
      JqFeatureExtractor jq, Map<String, Object> snapshot) {
    JsonNode node = MAPPER.valueToTree(snapshot);
    Map<String, FeatureValue> features = new LinkedHashMap<>();

    for (var entry : jq.featureExpressions().entrySet()) {
      ValidationResult result = jqEvaluator.eval(entry.getValue(), node);
      if (!result.ok()) {
        LOG.warnf(
            "JQ feature extraction error for '%s' (expr: %s): %s",
            entry.getKey(), entry.getValue(), result.error());
        continue;
      }
      List<JsonNode> output = result.output();
      if (output == null || output.isEmpty()) {
        continue;
      }
      Object value = unwrap(output.get(0));
      if (value != null) {
        features.put(entry.getKey(), FeatureValue.of(value));
      }
    }
    return features;
  }

  private Map<String, String> buildRoutingKeyMap(CaseDefinition definition) {
    Map<String, String> map = new LinkedHashMap<>();
    for (Binding binding : definition.getBindings()) {
      switch (binding.target()) {
        case CapabilityTarget ct -> map.put(binding.getName(), ct.capability().name());
        case io.casehub.api.model.HumanTaskTarget ht -> map.put(binding.getName(), null);
        default -> {
          /* SubCase, Extension — not retained */
        }
      }
    }
    return map;
  }

  private PlanTrace toPlanTrace(
      PlanItemRecord record, Map<String, String> capabilityNameMap, int priority) {
    return new PlanTrace(
        record.bindingName(),
        capabilityNameMap.get(record.bindingName()),
        record.executorName(),
        OUTCOME_MAP.getOrDefault(record.status(), RoutingOutcome.FAILURE).name(),
        priority,
        Map.of(),
        record.variantId());
  }

  private static Object unwrap(JsonNode node) {
    if (node == null || node.isNull() || node.isMissingNode()) {
      return null;
    }
    if (node.isTextual()) {
      return node.asText();
    }
    if (node.isInt()) {
      return node.asInt();
    }
    if (node.isLong()) {
      return node.asLong();
    }
    if (node.isDouble() || node.isFloat()) {
      return node.asDouble();
    }
    if (node.isBoolean()) {
      return node.asBoolean();
    }
    if (node.isNumber()) {
      return node.numberValue();
    }
    return node.toString();
  }
}
