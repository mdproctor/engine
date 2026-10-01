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

import io.casehub.api.model.improvement.ImprovementConfig;
import io.casehub.api.model.improvement.ImprovementRequest;
import io.casehub.api.spi.improvement.ImprovementProposalSource;
import io.casehub.engine.common.internal.signal.SignalRegistry;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@ApplicationScoped
public class SignalConsensusProposalSource implements ImprovementProposalSource {

  private final SignalRegistry signalRegistry;
  private final ImprovementSignalContext signalContext;

  public SignalConsensusProposalSource(
      SignalRegistry signalRegistry, ImprovementSignalContext signalContext) {
    this.signalRegistry = signalRegistry;
    this.signalContext = signalContext;
  }

  @Override
  public String sourceId() {
    return "signal-consensus";
  }

  @Override
  public String domainId() {
    return "code-evolution";
  }

  @Override
  public List<ImprovementRequest> propose(UUID caseId, String tenancyId, ImprovementConfig config) {
    String namespace = config.effectiveSignalNamespace();
    int minSources = config.effectiveConsensusMinSources();
    var consensus = signalRegistry.consensusSignals(caseId, minSources, 0.01);

    List<ImprovementRequest> proposals = new ArrayList<>();
    for (var entry : consensus.entrySet()) {
      String signalName = entry.getKey();
      if (!signalName.startsWith(namespace + ":")) {
        continue;
      }
      signalContext.get(caseId, signalName).ifPresent(proposals::add);
    }
    return proposals;
  }
}
