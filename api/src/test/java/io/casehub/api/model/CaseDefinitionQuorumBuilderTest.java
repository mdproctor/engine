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

import io.casehub.api.spi.QuorumConfig;
import org.junit.jupiter.api.Test;

class CaseDefinitionQuorumBuilderTest {

  @Test
  void builder_withQuorumConfig_setsDefaultQuorum() {
    QuorumConfig quorum = new QuorumConfig(3, 2, OnThresholdReached.KEEP, false);

    CaseDefinition def =
        CaseDefinition.builder()
            .namespace("test")
            .name("Quorum Test")
            .version("1.0.0")
            .defaultQuorum(quorum)
            .build();

    assertThat(def.getDefaultQuorum()).isNotNull();
    assertThat(def.getDefaultQuorum()).isEqualTo(quorum);
    assertThat(def.getDefaultQuorum().instances()).isEqualTo(3);
    assertThat(def.getDefaultQuorum().required()).isEqualTo(2);
    assertThat(def.getDefaultQuorum().onThresholdReached()).isEqualTo(OnThresholdReached.KEEP);
    assertThat(def.getDefaultQuorum().allowSameAssignee()).isFalse();
  }

  @Test
  void builder_withoutQuorumConfig_returnsNull() {
    CaseDefinition def =
        CaseDefinition.builder().namespace("test").name("No Quorum Test").version("1.0.0").build();

    assertThat(def.getDefaultQuorum()).isNull();
  }

  @Test
  void builder_withQuorumConfigMajority_setsCorrectly() {
    QuorumConfig quorum = QuorumConfig.majority(5);

    CaseDefinition def =
        CaseDefinition.builder()
            .namespace("test")
            .name("Majority Quorum Test")
            .version("1.0.0")
            .defaultQuorum(quorum)
            .build();

    assertThat(def.getDefaultQuorum()).isNotNull();
    assertThat(def.getDefaultQuorum().instances()).isEqualTo(5);
    assertThat(def.getDefaultQuorum().required()).isEqualTo(3); // (5/2) + 1
    assertThat(def.getDefaultQuorum().onThresholdReached()).isNull();
    assertThat(def.getDefaultQuorum().allowSameAssignee()).isFalse();
  }
}
