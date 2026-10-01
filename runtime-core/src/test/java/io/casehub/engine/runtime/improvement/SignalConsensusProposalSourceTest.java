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
package io.casehub.engine.runtime.improvement;

import static org.assertj.core.api.Assertions.assertThat;

import io.casehub.api.model.improvement.ImprovementConfig;
import io.casehub.api.model.improvement.ImprovementRequest;
import io.casehub.engine.common.internal.signal.SignalRegistry;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class SignalConsensusProposalSourceTest {

  private SignalRegistry signalRegistry;
  private ImprovementSignalContext signalContext;
  private SignalConsensusProposalSource source;
  private final UUID caseId = UUID.randomUUID();
  private final ImprovementConfig config =
      new ImprovementConfig("improvement", 2, null, null, null);

  @BeforeEach
  void setUp() {
    signalRegistry = new SignalRegistry();
    signalContext = new ImprovementSignalContext();
    source = new SignalConsensusProposalSource(signalRegistry, signalContext);
  }

  @Test
  void sourceIdAndDomainId() {
    assertThat(source.sourceId()).isEqualTo("signal-consensus");
    assertThat(source.domainId()).isEqualTo("code-evolution");
  }

  @Test
  void proposesFromConsensusSignals() {
    signalRegistry.deposit(
        caseId, "improvement:dep-update:lodash", 1.0, Duration.ofHours(1), "source-a", 100);
    signalRegistry.deposit(
        caseId, "improvement:dep-update:lodash", 1.0, Duration.ofHours(1), "source-b", 100);
    var request =
        new ImprovementRequest(
            "upgrade", "dependency-update", "lodash", 5, Map.of(), "code-evolution");
    signalContext.register(caseId, "improvement:dep-update:lodash", request);

    var proposals = source.propose(caseId, "t1", config);
    assertThat(proposals).hasSize(1);
    assertThat(proposals.get(0).category()).isEqualTo("dependency-update");
  }

  @Test
  void filtersNonMatchingNamespace() {
    signalRegistry.deposit(caseId, "other:some-signal", 1.0, Duration.ofHours(1), "source-a", 100);
    signalRegistry.deposit(caseId, "other:some-signal", 1.0, Duration.ofHours(1), "source-b", 100);
    var request = new ImprovementRequest("upgrade", "dep", "target", 5, Map.of(), null);
    signalContext.register(caseId, "other:some-signal", request);

    var proposals = source.propose(caseId, "t1", config);
    assertThat(proposals).isEmpty();
  }

  @Test
  void emptyConsensusReturnsEmpty() {
    var proposals = source.propose(caseId, "t1", config);
    assertThat(proposals).isEmpty();
  }

  @Test
  void missingSignalContextSkipped() {
    signalRegistry.deposit(
        caseId, "improvement:dep-update:lodash", 1.0, Duration.ofHours(1), "source-a", 100);
    signalRegistry.deposit(
        caseId, "improvement:dep-update:lodash", 1.0, Duration.ofHours(1), "source-b", 100);

    var proposals = source.propose(caseId, "t1", config);
    assertThat(proposals).isEmpty();
  }
}
