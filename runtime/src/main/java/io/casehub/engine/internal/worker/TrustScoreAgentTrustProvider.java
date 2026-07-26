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
package io.casehub.engine.internal.worker;

import io.casehub.ledger.api.spi.TrustScoreSource;
import io.casehub.neocortex.memory.cbr.AgentTrustProvider;
import io.quarkus.arc.DefaultBean;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import java.util.OptionalDouble;

/**
 * Bridges the neocortex {@link AgentTrustProvider} SPI to the ledger's {@link TrustScoreSource}.
 * Used by {@code TrustWeightedCbrCaseMemoryStore} to weight retrieved cases by the producing
 * agent's current trust score.
 *
 * <p>{@code @DefaultBean} — yields to any consumer-provided implementation. Returns empty when
 * {@code TrustScoreSource} is not on the classpath.
 */
@DefaultBean
@ApplicationScoped
public class TrustScoreAgentTrustProvider implements AgentTrustProvider {

  private final Instance<TrustScoreSource> trustScoreSource;

  @Inject
  public TrustScoreAgentTrustProvider(Instance<TrustScoreSource> trustScoreSource) {
    this.trustScoreSource = trustScoreSource;
  }

  @Override
  public OptionalDouble currentTrustScore(String agentId) {
    if (agentId == null || trustScoreSource.isUnsatisfied()) {
      return OptionalDouble.empty();
    }
    return trustScoreSource.get().globalScore(agentId);
  }
}
