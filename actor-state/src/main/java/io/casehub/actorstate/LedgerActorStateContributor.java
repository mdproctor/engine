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
package io.casehub.actorstate;

import io.casehub.ledger.runtime.service.TrustGateService;
import io.casehub.platform.api.actor.ActorStateAccumulator;
import io.casehub.platform.api.actor.ActorStateContributor;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.Map;

/** Contributes global and capability trust scores from casehub-ledger. */
@ApplicationScoped
public class LedgerActorStateContributor implements ActorStateContributor {

  @Inject TrustGateService trustGateService;

  @Override
  public String sourceName() {
    return "ledger";
  }

  @Override
  public void contribute(final String actorId, final ActorStateAccumulator acc) {
    // Atomic: collect all data before calling accumulator methods.
    // findScore().map(s -> s.trustScore): primitive double boxed to Double.
    // null means no score computed yet — distinct from 0.0 (zero trust).
    final Double globalScore =
        trustGateService.findScore(actorId).map(s -> s.trustScore).orElse(null);
    final Map<String, Double> capScores = trustGateService.allCapabilityScores(actorId);
    acc.trustScore(globalScore);
    capScores.forEach(acc::capabilityScore);
  }
}
