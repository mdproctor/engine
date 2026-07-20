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
import io.smallrye.mutiny.Uni;
import java.util.List;
import java.util.UUID;

/**
 * Reactive mirror of {@link CaseChannelProvider} — all methods return {@link Uni}.
 *
 * <p>Creates and manages communication channels for workers on a case. The no-op default returns
 * sentinel channels with {@code backendType = "none"} and treats all write operations as no-ops.
 */
public interface ReactiveCaseChannelProvider {

  /**
   * Open a new channel for the given case.
   *
   * @param caseId the case instance ID
   * @param purpose human-readable description of the channel's purpose
   * @return a {@code Uni} emitting the opened channel reference
   */
  Uni<CaseChannel> openChannel(UUID caseId, String purpose);

  /**
   * Post a message to a channel.
   *
   * @param channel the channel reference returned by {@link #openChannel}
   * @param from sender identity (worker ID or "human")
   * @param content message content
   * @param type the intent type of the message; {@code null} if unspecified
   * @param correlationId correlation identifier; {@code null} if unspecified
   * @param deadline ISO-8601 deadline; {@code null} if no deadline
   * @param target the intended recipient (e.g. worker name / agent ID); {@code null} if untargeted
   * @return a {@code Uni} completing with {@code null} on success
   */
  Uni<Void> postToChannel(
      CaseChannel channel,
      String from,
      String content,
      MessageType type,
      String correlationId,
      String deadline,
      String target);

  /** Post a message to a channel. Delegates to the 7-param overload with nulls. */
  default Uni<Void> postToChannel(CaseChannel channel, String from, String content) {
    return postToChannel(channel, from, content, null, null, null, null);
  }

  /**
   * Close a channel. No-op if the channel is unknown or already closed.
   *
   * @param channel the channel to close
   * @return a {@code Uni} completing with {@code null} on success
   */
  Uni<Void> closeChannel(CaseChannel channel);

  /**
   * List all channels currently open for the given case.
   *
   * @param caseId the case instance ID
   * @return a {@code Uni} emitting the list of open channels, empty if none
   */
  Uni<List<CaseChannel>> listChannels(UUID caseId);
}
