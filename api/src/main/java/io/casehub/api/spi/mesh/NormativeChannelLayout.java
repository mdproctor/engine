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
 * Canonical 3-channel agent mesh layout: work / observe / oversight.
 *
 * <p>Type enforcement per protocols PP-20260604-a7ad99 and PP-20260508-a15390:
 *
 * <ul>
 *   <li>{@code work} — all obligation-carrying types; unrestricted
 *   <li>{@code observe} — {@link MessageType#EVENT} only (telemetry; hard-blocked from obligation
 *       types)
 *   <li>{@code oversight} — {@code deniedTypes = {EVENT}} (advisory enforcement; all
 *       obligation-carrying types permitted)
 * </ul>
 *
 * <p>{@code caseId} and {@code definition} are both ignored — the layout is
 * case-definition-agnostic.
 */
public final class NormativeChannelLayout implements CaseChannelLayout {

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
            "Telemetry — EVENT only, no obligations created"),
        new CaseChannelLayout.ChannelSpec(
            "oversight",
            ChannelSemantic.APPEND,
            null,
            // If a new MessageType is added to Qhorus with no commitment effect (like EVENT),
            // add it here. This comment is the mechanical anchor for that obligation.
            Set.of(MessageType.EVENT),
            "Human governance — all obligation-carrying types; no telemetry"));
  }
}
