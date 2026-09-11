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
package io.casehub.engine.inbound;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.casehub.api.context.ContextBridge;
import io.casehub.api.engine.CaseHubRuntime;
import io.casehub.api.model.CaseDefinition;
import io.casehub.api.model.InboundSignalMapping;
import io.casehub.api.model.SignalType;
import io.casehub.api.model.evaluator.JQExpressionEvaluator;
import io.casehub.api.spi.CaseCorrelationResolver;
import io.casehub.connectors.InboundMessage;
import io.casehub.engine.common.internal.context.BridgeResolver;
import io.casehub.engine.common.internal.jq.JQEvaluator;
import io.casehub.engine.common.internal.jq.ValidationResult;
import io.casehub.engine.common.spi.CaseDefinitionRegistry;
import io.casehub.platform.api.expression.ExpressionEvaluator;
import io.casehub.platform.api.routing.StrategyResolver;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.jboss.logging.Logger;

/**
 * Bridges inbound connector messages to typed case signals.
 *
 * <p>Receives {@code InboundMessage} from connectors and routes matching messages to cases via
 * {@link CaseHubRuntime#signal(UUID, SignalType, Object)}.
 *
 * <p>Maintains an in-memory index keyed by {@code connectorType} for O(1) message dispatch. Index
 * is populated at startup from {@link CaseDefinitionRegistry#allDefinitions()} and updated
 * incrementally as definitions register.
 *
 * <p>Uses {@code bridge.deserialise()} directly (NOT {@code BridgeResolver.deserialise()}) because
 * connector data is external — the {@code $dataRef} discriminator is an internal engine convention
 * that external systems do not produce.
 */
public class InboundSignalBridge {

  private static final Logger LOG = Logger.getLogger(InboundSignalBridge.class);
  private static final ObjectMapper MAPPER = new ObjectMapper();

  private final Optional<CaseDefinitionRegistry> registry;
  private final Optional<CaseHubRuntime> runtime;
  private final BridgeResolver bridgeResolver;
  private final StrategyResolver strategyResolver;
  private final JQEvaluator jqEvaluator;

  private final Map<String, List<MappingEntry>> index = new ConcurrentHashMap<>();

  record MappingEntry(
      InboundSignalMapping mapping, CaseDefinition definition, SignalType<?> signalType) {}

  public InboundSignalBridge(
      Optional<CaseDefinitionRegistry> registry,
      Optional<CaseHubRuntime> runtime,
      BridgeResolver bridgeResolver,
      StrategyResolver strategyResolver,
      JQEvaluator jqEvaluator) {
    this.registry = registry;
    this.runtime = runtime;
    this.bridgeResolver = bridgeResolver;
    this.strategyResolver = strategyResolver;
    this.jqEvaluator = jqEvaluator;
    init();
  }

  void init() {
    if (registry.isEmpty()) {
      return;
    }
    for (CaseDefinition def : registry.get().allDefinitions()) {
      indexDefinition(def);
    }
  }

  public void indexDefinition(CaseDefinition definition) {
    for (InboundSignalMapping mapping : definition.getInboundMappings()) {
      SignalType<?> signalType =
          definition.getSignals().stream()
              .filter(s -> s.name().equals(mapping.signalName()))
              .findFirst()
              .orElse(null);
      if (signalType == null) continue;
      index
          .computeIfAbsent(mapping.connectorType(), k -> new ArrayList<>())
          .add(new MappingEntry(mapping, definition, signalType));
    }
  }

  public void onInboundMessage(InboundMessage message) {
    if (registry.isEmpty() || runtime.isEmpty()) {
      return;
    }

    List<MappingEntry> entries = index.get(message.connectorType());
    if (entries == null || entries.isEmpty()) {
      return;
    }

    JsonNode composite = buildComposite(message);

    for (MappingEntry entry : entries) {
      try {
        processMapping(entry, composite, message);
      } catch (Exception e) {
        LOG.warnf(
            e,
            "InboundSignalBridge: mapping '%s' failed for connectorType=%s — skipping",
            entry.mapping().signalName(),
            message.connectorType());
      }
    }
  }

  @SuppressWarnings("unchecked")
  private void processMapping(MappingEntry entry, JsonNode composite, InboundMessage message) {
    String correlationValue = evaluateJqAsString(composite, entry.mapping().correlation());

    CaseCorrelationResolver resolver =
        strategyResolver.resolve(
            CaseCorrelationResolver.class, entry.mapping().correlationResolver());
    UUID caseId = resolver.resolve(correlationValue, message.tenancyId());

    JsonNode payloadJson = evaluateJq(composite, entry.mapping().payload());

    ContextBridge<?> bridge = bridgeResolver.resolveByType(entry.signalType().payloadType());
    Object typedPayload = bridge.deserialise(payloadJson);

    runtime.get().signal(caseId, (SignalType) entry.signalType(), typedPayload);

    LOG.debugf(
        "InboundSignalBridge: routed %s message to case %s as signal '%s'",
        message.connectorType(), caseId, entry.mapping().signalName());
  }

  private JsonNode buildComposite(InboundMessage message) {
    ObjectNode node = MAPPER.createObjectNode();
    node.put("content", message.content());
    node.put("connectorType", message.connectorType());
    node.put("connectorId", message.connectorId());
    node.put("externalSenderId", message.externalSenderId());
    node.put("externalChannelRef", message.externalChannelRef());
    node.put("tenancyId", message.tenancyId());
    node.put("receivedAt", message.receivedAt() != null ? message.receivedAt().toString() : null);
    ObjectNode meta = MAPPER.createObjectNode();
    if (message.metadata() != null) {
      message.metadata().forEach(meta::put);
    }
    node.set("metadata", meta);
    return node;
  }

  private String evaluateJqAsString(JsonNode input, ExpressionEvaluator evaluator) {
    JsonNode result = evaluateJq(input, evaluator);
    if (result == null) return null;
    return result.isTextual() ? result.asText() : result.toString();
  }

  private JsonNode evaluateJq(JsonNode input, ExpressionEvaluator evaluator) {
    if (!(evaluator instanceof JQExpressionEvaluator jq)) {
      throw new UnsupportedOperationException(
          "Only JQ expressions supported for inbound signal mappings");
    }
    ValidationResult result = jqEvaluator.eval(jq.expression(), input);
    if (!result.ok()) {
      throw new RuntimeException("JQ evaluation failed: " + result.error());
    }
    return result.output().isEmpty() ? null : result.output().get(0);
  }
}
