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

import org.junit.jupiter.api.Test;

class AgentTaskTargetTest {

  @Test
  void builder_requiredFieldsOnly() {
    AgentTaskTarget target = AgentTaskTarget.builder().capability("sentiment-analysis").build();
    assertThat(target.capability()).isEqualTo("sentiment-analysis");
    assertThat(target.domain()).isNull();
    assertThat(target.inputMapping()).isNull();
    assertThat(target.outputMapping()).isNull();
    assertThat(target).isInstanceOf(BindingTarget.class);
  }

  @Test
  void builder_allFields() {
    AgentTaskTarget target =
        AgentTaskTarget.builder()
            .capability("sentiment-analysis")
            .domain("customer-support")
            .inputMapping(".ticket")
            .outputMapping(".analysis")
            .build();
    assertThat(target.capability()).isEqualTo("sentiment-analysis");
    assertThat(target.domain()).isEqualTo("customer-support");
    assertThat(target.inputMapping()).isNotNull();
    assertThat(target.outputMapping()).isNotNull();
  }

  @Test
  void builder_nullCapability_throws() {
    assertThatThrownBy(() -> AgentTaskTarget.builder().build())
        .isInstanceOf(NullPointerException.class);
  }

  @Test
  void builder_withModel() {
    AgentTaskTarget target =
        AgentTaskTarget.builder().capability("risk-assessment").model("claude-sonnet-5-5").build();
    assertThat(target.capability()).isEqualTo("risk-assessment");
    assertThat(target.model()).isEqualTo("claude-sonnet-5-5");
  }

  @Test
  void builder_withoutModel_isNull() {
    AgentTaskTarget target = AgentTaskTarget.builder().capability("risk-assessment").build();
    assertThat(target.model()).isNull();
  }
}
