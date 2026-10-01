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
package io.casehub.engine.internal.improvement;

import io.casehub.api.model.stigmergy.GatePolicy;
import io.casehub.engine.common.spi.GatePolicyStore;
import io.casehub.engine.common.spi.Resettable;
import io.quarkus.arc.DefaultBean;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@DefaultBean
@ApplicationScoped
public class InMemoryGatePolicyStore implements GatePolicyStore, Resettable {

  private final ConcurrentHashMap<UUID, GatePolicy> policies = new ConcurrentHashMap<>();

  @Override
  public void save(UUID caseId, GatePolicy policy, String tenancyId) {
    policies.put(caseId, policy);
  }

  @Override
  public GatePolicy find(UUID caseId, String tenancyId) {
    return policies.get(caseId);
  }

  @Override
  public void reset() {
    policies.clear();
  }
}
