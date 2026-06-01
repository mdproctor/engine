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
package io.casehub.api.model;

import java.util.Map;
import java.util.UUID;

/**
 * Opaque reference to a communication channel for workers on a case.
 *
 * <p>Backend-agnostic: a Qhorus implementation sets {@code backendType = "qhorus"} and populates
 * {@code properties} with Qhorus-specific metadata (e.g. endpoint URL). The {@code properties} map
 * is always immutable.
 *
 * <p>Channel names follow the convention {@code "case-{caseId}/{purpose}"} — use {@link
 * #channelName(UUID, String)} to construct and {@link #CASE_CHANNEL_PREFIX} to identify them. Both
 * the {@code CaseChannelProvider} implementation and the signal bridge rely on this format;
 * changing it here propagates to both.
 *
 * @throws IllegalArgumentException if id or backendType is blank, or if properties contains null
 *     values
 */
public record CaseChannel(
    String id, String name, String purpose, String backendType, Map<String, Object> properties) {

  /** Prefix shared by all case-scoped Qhorus channel names. */
  public static final String CASE_CHANNEL_PREFIX = "case-";

  /**
   * Constructs the canonical channel name for a case and purpose. Format: {@code
   * "case-{caseId}/{purpose}"}.
   */
  public static String channelName(UUID caseId, String purpose) {
    return CASE_CHANNEL_PREFIX + caseId + "/" + purpose;
  }

  /**
   * Constructs the canonical oversight channel name for a case. Equivalent to {@code
   * channelName(caseId, "oversight")}.
   *
   * <p>Oversight channels carry human governance decisions. See protocol {@code
   * qhorus-per-entity-governance-channels.md}.
   */
  public static String oversightChannelName(final UUID caseId) {
    return channelName(caseId, "oversight");
  }

  /**
   * Extracts the case UUID from a channel name that follows the {@code "case-{caseId}/{purpose}"}
   * convention.
   *
   * @param channelName the channel name; may be null
   * @return the case UUID, or null if channelName is null, does not start with {@link
   *     #CASE_CHANNEL_PREFIX}, or the UUID segment is malformed
   */
  public static UUID parseCaseId(final String channelName) {
    if (channelName == null || !channelName.startsWith(CASE_CHANNEL_PREFIX)) return null;
    final String rest = channelName.substring(CASE_CHANNEL_PREFIX.length());
    final String uuidStr = rest.contains("/") ? rest.substring(0, rest.indexOf('/')) : rest;
    try {
      return UUID.fromString(uuidStr);
    } catch (final IllegalArgumentException e) {
      return null;
    }
  }

  public CaseChannel {
    if (id == null || id.isBlank()) throw new IllegalArgumentException("id must not be blank");
    if (backendType == null || backendType.isBlank())
      throw new IllegalArgumentException("backendType must not be blank");
    if (properties != null) {
      properties.forEach(
          (k, v) -> {
            if (v == null)
              throw new IllegalArgumentException("properties must not contain null values");
          });
      properties = Map.copyOf(properties);
    } else {
      properties = Map.of();
    }
  }
}
