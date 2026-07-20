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
package io.casehub.api.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.casehub.api.model.evaluator.JQExpressionEvaluator;
import org.junit.jupiter.api.Test;

class InboundSignalMappingTest {

  @Test
  void builder_creates_mapping_with_jq_expressions() {
    var mapping =
        InboundSignalMapping.builder()
            .signalName("aml-alert")
            .connectorType("aml-system")
            .correlation(".metadata.caseRef")
            .payload(".content | fromjson")
            .correlationResolver("uuid")
            .build();

    assertThat(mapping.signalName()).isEqualTo("aml-alert");
    assertThat(mapping.connectorType()).isEqualTo("aml-system");
    assertThat(mapping.correlation()).isInstanceOf(JQExpressionEvaluator.class);
    assertThat(((JQExpressionEvaluator) mapping.correlation()).expression())
        .isEqualTo(".metadata.caseRef");
    assertThat(mapping.payload()).isInstanceOf(JQExpressionEvaluator.class);
    assertThat(mapping.correlationResolver()).isEqualTo("uuid");
  }

  @Test
  void builder_requires_signalName() {
    assertThatThrownBy(
            () ->
                InboundSignalMapping.builder()
                    .connectorType("slack")
                    .correlation(".x")
                    .payload(".y")
                    .build())
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  void builder_requires_connectorType() {
    assertThatThrownBy(
            () ->
                InboundSignalMapping.builder()
                    .signalName("alert")
                    .correlation(".x")
                    .payload(".y")
                    .build())
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  void builder_correlationResolver_defaults_to_null() {
    var mapping =
        InboundSignalMapping.builder()
            .signalName("alert")
            .connectorType("slack")
            .correlation(".x")
            .payload(".y")
            .build();

    assertThat(mapping.correlationResolver()).isNull();
  }

  @Test
  void caseDefinition_validates_signalName_matches_declared_signal() {
    assertThatThrownBy(
            () ->
                CaseDefinition.builder()
                    .namespace("test")
                    .name("test")
                    .version("1.0")
                    .signal(SignalType.of("alert", String.class))
                    .inboundMapping(
                        InboundSignalMapping.builder()
                            .signalName("unknown-signal")
                            .connectorType("slack")
                            .correlation(".x")
                            .payload(".y")
                            .build())
                    .build())
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("unknown-signal");
  }

  @Test
  void caseDefinition_accepts_valid_inboundMapping() {
    var def =
        CaseDefinition.builder()
            .namespace("test")
            .name("test")
            .version("1.0")
            .signal(SignalType.of("alert", String.class))
            .inboundMapping(
                InboundSignalMapping.builder()
                    .signalName("alert")
                    .connectorType("slack")
                    .correlation(".metadata.caseRef")
                    .payload(".content")
                    .build())
            .build();

    assertThat(def.getInboundMappings()).hasSize(1);
    assertThat(def.getInboundMappings().get(0).signalName()).isEqualTo("alert");
  }
}
