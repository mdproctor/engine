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
 * SPI: declares the Qhorus channel topology for an agent mesh case.
 *
 * <p>Implementations return one {@link ChannelSpec} per channel to create. The purpose field
 * becomes the channel name suffix; semantic, allowedTypes and deniedTypes are enforced at the
 * Qhorus layer.
 *
 * <p>{@code CaseDefinition definition} is passed as {@code null} at all current call sites — the
 * parameter exists for future strategies that may vary topology per case definition (e.g. a
 * definition with {@code requires_oversight: false} selecting {@link SimpleLayout}). Do not remove
 * it; it is an intentional extensibility point from the original SPI design (claudony#87).
 *
 * <p>Standard implementations: {@link NormativeChannelLayout} (3-channel: work/observe/oversight),
 * {@link SimpleLayout} (2-channel: work/observe). Select via {@link #named(String)} for
 * config-driven layout choice.
 */
public interface CaseChannelLayout {

  /**
   * Returns the channel specs for a case.
   *
   * @param caseId the case identifier
   * @param definition the case definition; may be {@code null} if not yet available
   */
  List<ChannelSpec> channelsFor(UUID caseId, CaseDefinition definition);

  /**
   * Factory for standard layouts by config string.
   *
   * <p>Valid values: {@code "normative"} → {@link NormativeChannelLayout}, {@code "simple"} →
   * {@link SimpleLayout}.
   *
   * @throws IllegalArgumentException for unknown config values
   */
  static CaseChannelLayout named(final String configValue) {
    if (configValue == null) {
      throw new IllegalArgumentException("Channel layout config value must not be null");
    }
    return switch (configValue) {
      case "normative" -> new NormativeChannelLayout();
      case "simple" -> new SimpleLayout();
      default -> throw new IllegalArgumentException("Unknown channel layout: " + configValue);
    };
  }

  /**
   * Specification for a single Qhorus channel in the agent mesh.
   *
   * @param purpose channel name suffix; e.g. {@code "work"}, {@code "observe"}, {@code "oversight"}
   * @param semantic channel semantic; always {@link ChannelSemantic#APPEND} for mesh channels
   * @param allowedTypes message types permitted; {@code null} = all types allowed. Callers must
   *     pass an unmodifiable set (e.g. {@link Set#of}) — not defensively copied; all standard
   *     implementations use {@link Set#of}.
   * @param deniedTypes message types explicitly denied; {@code null} = no denial. Denial wins when
   *     a type appears in both sets. If a new {@link MessageType} is added with no commitment
   *     effect (like EVENT), add it here for governance channels — this comment is the mechanical
   *     anchor for that obligation. Same unmodifiable-set contract as {@code allowedTypes}.
   * @param description human-readable channel description
   */
  record ChannelSpec(
      String purpose,
      ChannelSemantic semantic,
      Set<MessageType> allowedTypes,
      Set<MessageType> deniedTypes,
      String description) {}
}
