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

import io.casehub.api.model.CaseDefinition;
import io.casehub.qhorus.api.channel.ChannelSemantic;
import io.casehub.qhorus.api.message.MessageType;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * 2-channel agent mesh layout for cases that do not require a human governance gate.
 *
 * <p>Channels: {@code work} (unrestricted) and {@code observe} (EVENT-only telemetry). No {@code
 * oversight} channel.
 *
 * <p>{@code caseId} and {@code definition} are both ignored — the layout is
 * case-definition-agnostic.
 */
public final class SimpleLayout implements CaseChannelLayout {

  @Override
  public List<CaseChannelLayout.ChannelSpec> channelsFor(
      final UUID caseId, final CaseDefinition definition) {
    return List.of(
        new CaseChannelLayout.ChannelSpec(
            "work",
            ChannelSemantic.APPEND,
            null,
            null,
            "Primary coordination — all obligation-carrying message types"),
        new CaseChannelLayout.ChannelSpec(
            "observe",
            ChannelSemantic.APPEND,
            Set.of(MessageType.EVENT),
            null,
            "Telemetry — EVENT only, no obligations created"));
  }
}
