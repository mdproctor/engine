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
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNoException;

import io.casehub.api.spi.mesh.CaseChannelLayout.ChannelSpec;
import io.casehub.qhorus.api.channel.ChannelSemantic;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class CaseChannelLayoutContractTest {

  // ── SPI contract ───────────────────────────────────────────────────────

  @Test
  void channelsFor_withNullDefinition_mustNotThrow() {
    // CaseDefinition is always null at current call sites — intentional extensibility parameter
    CaseChannelLayout layout = new NormativeChannelLayout();
    assertThatNoException().isThrownBy(() -> layout.channelsFor(UUID.randomUUID(), null));
  }

  @Test
  void channelsFor_returnsNonNullList() {
    CaseChannelLayout layout = new NormativeChannelLayout();
    assertThat(layout.channelsFor(UUID.randomUUID(), null)).isNotNull();
  }

  @Test
  void channelsFor_noPurposeIsNull() {
    CaseChannelLayout layout = new NormativeChannelLayout();
    List<ChannelSpec> specs = layout.channelsFor(UUID.randomUUID(), null);
    assertThat(specs).extracting(ChannelSpec::purpose).doesNotContainNull();
  }

  @Test
  void channelsFor_noDuplicatePurposes() {
    CaseChannelLayout layout = new NormativeChannelLayout();
    List<ChannelSpec> specs = layout.channelsFor(UUID.randomUUID(), null);
    assertThat(specs).extracting(ChannelSpec::purpose).doesNotHaveDuplicates();
  }

  @Test
  void channelsFor_allUseAppendSemantic() {
    CaseChannelLayout layout = new NormativeChannelLayout();
    List<ChannelSpec> specs = layout.channelsFor(UUID.randomUUID(), null);
    assertThat(specs).extracting(ChannelSpec::semantic).containsOnly(ChannelSemantic.APPEND);
  }

  // ── named() factory ───────────────────────────────────────────────────

  @Test
  void named_normative_producesThreeChannels() {
    CaseChannelLayout layout = CaseChannelLayout.named("normative");
    assertThat(layout.channelsFor(UUID.randomUUID(), null)).hasSize(3);
  }

  @Test
  void named_normative_hasWorkObserveOversight() {
    CaseChannelLayout layout = CaseChannelLayout.named("normative");
    assertThat(layout.channelsFor(UUID.randomUUID(), null))
        .extracting(ChannelSpec::purpose)
        .containsExactly("work", "observe", "oversight");
  }

  @Test
  void named_simple_producesTwoChannels() {
    CaseChannelLayout layout = CaseChannelLayout.named("simple");
    assertThat(layout.channelsFor(UUID.randomUUID(), null)).hasSize(2);
  }

  @Test
  void named_simple_hasWorkAndObserve_notOversight() {
    CaseChannelLayout layout = CaseChannelLayout.named("simple");
    assertThat(layout.channelsFor(UUID.randomUUID(), null))
        .extracting(ChannelSpec::purpose)
        .containsExactly("work", "observe")
        .doesNotContain("oversight");
  }

  @Test
  void named_unknown_throwsIllegalArgumentException() {
    assertThatIllegalArgumentException()
        .isThrownBy(() -> CaseChannelLayout.named("unknown-layout"));
  }
}
