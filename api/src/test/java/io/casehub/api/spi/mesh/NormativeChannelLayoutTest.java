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
package io.casehub.api.spi.mesh;

import static org.assertj.core.api.Assertions.assertThat;

import io.casehub.api.spi.mesh.CaseChannelLayout.ChannelSpec;
import io.casehub.qhorus.api.channel.ChannelSemantic;
import io.casehub.qhorus.api.message.MessageType;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class NormativeChannelLayoutTest {

  private final NormativeChannelLayout layout = new NormativeChannelLayout();

  @Test
  void channelsFor_returnsThreeChannels() {
    List<ChannelSpec> specs = layout.channelsFor(UUID.randomUUID(), null);
    assertThat(specs).hasSize(3);
  }

  @Test
  void channelsFor_purposes_areWorkObserveOversight() {
    List<ChannelSpec> specs = layout.channelsFor(UUID.randomUUID(), null);
    assertThat(specs)
        .extracting(ChannelSpec::purpose)
        .containsExactly("work", "observe", "oversight");
  }

  @Test
  void channelsFor_allUseAppendSemantic() {
    List<ChannelSpec> specs = layout.channelsFor(UUID.randomUUID(), null);
    assertThat(specs).extracting(ChannelSpec::semantic).containsOnly(ChannelSemantic.APPEND);
  }

  @Test
  void channelsFor_observeChannel_allowsOnlyEventType() {
    ChannelSpec observe =
        layout.channelsFor(UUID.randomUUID(), null).stream()
            .filter(s -> s.purpose().equals("observe"))
            .findFirst()
            .orElseThrow();
    assertThat(observe.allowedTypes()).containsExactlyInAnyOrder(MessageType.EVENT);
  }

  @Test
  void channelsFor_oversightChannel_allowedTypesIsNull() {
    // oversight is open to all obligation-carrying types; EVENT denied below
    ChannelSpec oversight =
        layout.channelsFor(UUID.randomUUID(), null).stream()
            .filter(s -> s.purpose().equals("oversight"))
            .findFirst()
            .orElseThrow();
    assertThat(oversight.allowedTypes()).isNull();
  }

  @Test
  void channelsFor_oversightChannel_deniedTypesContainsEventOnly() {
    // per PP-20260604-a7ad99: oversight uses deniedTypes=EVENT (advisory enforcement)
    ChannelSpec oversight =
        layout.channelsFor(UUID.randomUUID(), null).stream()
            .filter(s -> s.purpose().equals("oversight"))
            .findFirst()
            .orElseThrow();
    assertThat(oversight.deniedTypes()).containsExactlyInAnyOrder(MessageType.EVENT);
  }

  @Test
  void channelsFor_workChannel_allowedTypesIsNull() {
    ChannelSpec work =
        layout.channelsFor(UUID.randomUUID(), null).stream()
            .filter(s -> s.purpose().equals("work"))
            .findFirst()
            .orElseThrow();
    assertThat(work.allowedTypes()).isNull();
  }

  @Test
  void channelsFor_workChannel_deniedTypesIsNull() {
    ChannelSpec work =
        layout.channelsFor(UUID.randomUUID(), null).stream()
            .filter(s -> s.purpose().equals("work"))
            .findFirst()
            .orElseThrow();
    assertThat(work.deniedTypes()).isNull();
  }

  @Test
  void channelsFor_observeChannel_deniedTypesIsNull() {
    // deniedTypes null on observe — EVENT is already the only allowed type; denial is redundant
    ChannelSpec observe =
        layout.channelsFor(UUID.randomUUID(), null).stream()
            .filter(s -> s.purpose().equals("observe"))
            .findFirst()
            .orElseThrow();
    assertThat(observe.deniedTypes()).isNull();
  }

  @Test
  void channelsFor_caseIdIsIgnored_returnsConsistentSpecs() {
    UUID id1 = UUID.randomUUID();
    UUID id2 = UUID.randomUUID();
    assertThat(layout.channelsFor(id1, null))
        .usingRecursiveComparison()
        .isEqualTo(layout.channelsFor(id2, null));
  }
}
