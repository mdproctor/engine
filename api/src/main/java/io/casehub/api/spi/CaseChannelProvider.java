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
package io.casehub.api.spi;

import io.casehub.api.model.CaseChannel;
import io.casehub.qhorus.api.message.MessageType;
import java.util.List;
import java.util.UUID;

/**
 * Creates and manages communication channels for workers on a case.
 *
 * <p>The {@link CaseChannel} record carries a {@code backendType} field and extensible {@code
 * properties} map so implementations can attach backend-specific metadata without coupling the SPI
 * to any particular channel system.
 */
public interface CaseChannelProvider {

  /**
   * Open or retrieve a channel for the given case and purpose.
   *
   * <p><strong>Idempotency contract:</strong> calling this method more than once with the same
   * {@code caseId} and {@code purpose} must not throw and must return a usable channel. The engine
   * calls {@code openChannel} on every worker dispatch event ({@code WorkerScheduleEventHandler})
   * and also at case start ({@code CaseStartedEventHandler}). Implementations must treat this as
   * get-or-create, not unconditional create. See casehubio/engine#323.
   *
   * @param caseId the case instance ID
   * @param purpose human-readable description of the channel's purpose
   * @return the opened channel reference
   */
  CaseChannel openChannel(UUID caseId, String purpose);

  /**
   * Post a message to a channel.
   *
   * @param channel the channel reference returned by {@link #openChannel}
   * @param from sender identity (worker ID or "human")
   * @param content message content
   * @param type the intent type of the message (e.g. {@link MessageType#COMMAND}); {@code null} if
   *     unspecified
   * @param correlationId correlation identifier for causal linkage (e.g. eventLogId); {@code null}
   *     if unspecified
   * @param deadline ISO-8601 deadline for temporal obligation tracking; {@code null} if no deadline
   * @param target the intended recipient (e.g. worker name / agent ID); {@code null} if untargeted
   * @deprecated Use {@link #postToChannel(CaseChannel, PostRequest)} instead
   */
  @Deprecated
  void postToChannel(
      CaseChannel channel,
      String from,
      String content,
      MessageType type,
      String correlationId,
      String deadline,
      String target);

  /**
   * Post a message to a channel.
   *
   * @param channel the channel reference returned by {@link #openChannel}
   * @param request the message content and metadata
   */
  default void postToChannel(CaseChannel channel, PostRequest request) {
    postToChannel(
        channel,
        request.from(),
        request.content(),
        request.type(),
        request.correlationId(),
        request.deadline(),
        request.target());
  }

  /**
   * Post a message to a channel. Delegates to {@link #postToChannel(CaseChannel, String, String,
   * MessageType, String, String, String)} with {@code type}, {@code correlationId}, {@code
   * deadline}, and {@code target} all {@code null}.
   *
   * @deprecated Use {@link #postToChannel(CaseChannel, PostRequest)} instead
   */
  @Deprecated
  default void postToChannel(CaseChannel channel, String from, String content) {
    postToChannel(channel, from, content, null, null, null, null);
  }

  /**
   * Close a channel. No-op if the channel is unknown or already closed.
   *
   * @param channel the channel to close
   */
  void closeChannel(CaseChannel channel);

  /**
   * List all channels currently open for the given case.
   *
   * @param caseId the case instance ID
   * @return list of open channels, empty if none
   */
  List<CaseChannel> listChannels(UUID caseId);
}
