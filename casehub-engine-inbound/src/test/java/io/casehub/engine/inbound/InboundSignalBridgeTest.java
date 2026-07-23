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

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import io.casehub.api.context.ContextBridge;
import io.casehub.api.engine.CaseHubRuntime;
import io.casehub.api.model.CaseDefinition;
import io.casehub.api.model.InboundSignalMapping;
import io.casehub.api.model.SignalType;
import io.casehub.api.spi.CaseCorrelationResolver;
import io.casehub.connectors.InboundMessage;
import io.casehub.engine.common.internal.context.BridgeResolver;
import io.casehub.engine.common.internal.jq.JQEvaluator;
import io.casehub.engine.common.internal.jq.ValidationResult;
import io.casehub.engine.common.spi.CaseDefinitionRegistry;
import io.casehub.platform.api.routing.StrategyResolver;
import io.smallrye.mutiny.Uni;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class InboundSignalBridgeTest {

  record TestAlert(String alertId, String severity) {}

  @Mock CaseDefinitionRegistry registry;
  @Mock CaseHubRuntime runtime;
  @Mock BridgeResolver bridgeResolver;
  @Mock StrategyResolver strategyResolver;
  @Mock JQEvaluator jqEvaluator;

  private InboundSignalBridge bridge;

  @BeforeEach
  void setUp() {
    bridge = new InboundSignalBridge();
    bridge.registry = wrapInstance(registry);
    bridge.runtime = wrapInstance(runtime);
    bridge.bridgeResolver = bridgeResolver;
    bridge.strategyResolver = strategyResolver;
    bridge.jqEvaluator = jqEvaluator;
  }

  @Test
  void routes_inbound_message_to_typed_signal() {
    UUID caseId = UUID.randomUUID();
    var signalType = SignalType.of("alert", TestAlert.class);
    var definition =
        CaseDefinition.builder()
            .namespace("test")
            .name("test")
            .version("1")
            .signal(signalType)
            .inboundMapping(
                InboundSignalMapping.builder()
                    .signalName("alert")
                    .connectorType("aml-system")
                    .correlation(".metadata.caseRef")
                    .payload(".content | fromjson")
                    .build())
            .build();

    bridge.indexDefinition(definition);

    var uuidResolver = mock(CaseCorrelationResolver.class);
    when(strategyResolver.resolve(eq(CaseCorrelationResolver.class), any()))
        .thenReturn(uuidResolver);
    when(uuidResolver.resolve(eq(caseId.toString()), eq("tenant1")))
        .thenReturn(Uni.createFrom().item(caseId));

    when(jqEvaluator.eval(eq(".metadata.caseRef"), any(JsonNode.class)))
        .thenReturn(
            ValidationResult.ok(
                List.of(
                    new com.fasterxml.jackson.databind.ObjectMapper()
                        .getNodeFactory()
                        .textNode(caseId.toString()))));

    var alertJson =
        new com.fasterxml.jackson.databind.ObjectMapper()
            .createObjectNode()
            .put("alertId", "A-1")
            .put("severity", "HIGH");
    when(jqEvaluator.eval(eq(".content | fromjson"), any(JsonNode.class)))
        .thenReturn(ValidationResult.ok(List.of(alertJson)));

    var contextBridge = mock(ContextBridge.class);
    when(bridgeResolver.resolveByType(TestAlert.class)).thenReturn(contextBridge);
    var alert = new TestAlert("A-1", "HIGH");
    when(contextBridge.deserialise(alertJson)).thenReturn(alert);

    var message =
        new InboundMessage(
            "aml-1",
            "aml-system",
            null,
            null,
            "{\"alertId\":\"A-1\",\"severity\":\"HIGH\"}",
            List.of(),
            Instant.now(),
            Map.of("caseRef", caseId.toString()),
            "tenant1");

    bridge.onInboundMessage(message);

    verify(runtime).signal(caseId, signalType, alert);
  }

  @Test
  void ignores_message_with_unmatched_connectorType() {
    var message =
        new InboundMessage(
            "x", "unknown-type", null, null, "{}", List.of(), Instant.now(), Map.of(), "t1");

    bridge.onInboundMessage(message);

    verifyNoInteractions(runtime);
  }

  @Test
  void handles_mapping_failure_gracefully() {
    var signalType = SignalType.of("alert", String.class);
    var definition =
        CaseDefinition.builder()
            .namespace("test")
            .name("test")
            .version("1")
            .signal(signalType)
            .inboundMapping(
                InboundSignalMapping.builder()
                    .signalName("alert")
                    .connectorType("slack")
                    .correlation(".bad")
                    .payload(".bad")
                    .build())
            .build();

    bridge.indexDefinition(definition);

    when(jqEvaluator.eval(eq(".bad"), any(JsonNode.class)))
        .thenReturn(ValidationResult.error("JQ parse error"));

    var message =
        new InboundMessage(
            "s1", "slack", null, null, "text", List.of(), Instant.now(), Map.of(), "t1");

    bridge.onInboundMessage(message);

    verifyNoInteractions(runtime);
  }

  @SuppressWarnings("unchecked")
  private static <T> jakarta.enterprise.inject.Instance<T> wrapInstance(T value) {
    var instance =
        mock(
            jakarta.enterprise.inject.Instance.class, org.mockito.Mockito.withSettings().lenient());
    when(instance.isUnsatisfied()).thenReturn(false);
    when(instance.get()).thenReturn(value);
    return instance;
  }
}
