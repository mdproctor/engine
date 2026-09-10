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

import io.casehub.eidos.api.AgentDescriptor;
import io.casehub.eidos.api.AgentMatch;
import io.casehub.eidos.api.AgentQuery;
import io.casehub.eidos.api.AgentRegistry;
import java.util.List;
import java.util.Optional;

public class NoOpAgentRegistry implements AgentRegistry {

  @Override
  public void register(AgentDescriptor descriptor) {}

  @Override
  public Optional<AgentDescriptor> findById(String agentId, String tenancyId) {
    return Optional.empty();
  }

  @Override
  public List<AgentMatch> find(AgentQuery query) {
    return List.of();
  }
}
