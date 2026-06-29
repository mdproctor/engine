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

import io.casehub.api.spi.ProvisionerConfigRegistry;
import io.quarkus.arc.DefaultBean;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.Map;
import java.util.Set;

/**
 * No-op ProvisionerConfigRegistry. Returns empty collections for all queries. Active by default —
 * displace with an @Alternative when a real implementation is co-deployed.
 */
@DefaultBean
@ApplicationScoped
public class NoOpProvisionerConfigRegistry implements ProvisionerConfigRegistry {

  @Override
  public Map<String, Object> configFor(String providerName, String agentId) {
    return Map.of();
  }

  @Override
  public Set<String> declaredAgentIds(String providerName) {
    return Set.of();
  }
}
