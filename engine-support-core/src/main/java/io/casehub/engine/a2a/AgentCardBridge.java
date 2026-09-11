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
package io.casehub.engine.a2a;

import io.casehub.eidos.api.AgentCapability;
import io.casehub.eidos.api.AgentDescriptor;
import java.util.List;

public final class AgentCardBridge {

  private AgentCardBridge() {}

  public static AgentDescriptor toDescriptor(String workerName, AgentCard card, String tenancyId) {
    List<AgentCapability> capabilities =
        card.skills() != null
            ? card.skills().stream()
                .map(
                    skill ->
                        new AgentCapability(
                            skill.name() != null ? skill.name() : skill.id(),
                            skill.description(),
                            null,
                            null,
                            null,
                            null,
                            null,
                            null,
                            null,
                            null,
                            null))
                .toList()
            : List.of();

    return AgentDescriptor.builder()
        .agentId(workerName)
        .name(card.name() != null ? card.name() : workerName)
        .version(card.version())
        .provider(card.provider())
        .capabilities(capabilities)
        .tenancyId(tenancyId)
        .briefing(card.description())
        .build();
  }
}
