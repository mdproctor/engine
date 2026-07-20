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

import io.casehub.api.model.CaseChannel;
import io.casehub.api.spi.CaseChannelProvider;
import io.casehub.qhorus.api.message.MessageType;
import io.quarkus.arc.DefaultBean;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Default no-op CaseChannelProvider. Returns sentinel channels with {@code backendType = "none"}.
 * All write operations are no-ops.
 */
@DefaultBean
@ApplicationScoped
public class NoOpCaseChannelProvider implements CaseChannelProvider {

  @Override
  public CaseChannel openChannel(UUID caseId, String purpose) {
    return new CaseChannel(caseId + "/" + purpose, purpose, purpose, "none", Map.of());
  }

  @Override
  public void postToChannel(
      CaseChannel channel,
      String from,
      String content,
      MessageType type,
      String correlationId,
      String deadline,
      String target) {
    // intentional no-op
  }

  @Override
  public void closeChannel(CaseChannel channel) {
    // intentional no-op
  }

  @Override
  public List<CaseChannel> listChannels(UUID caseId) {
    return List.of();
  }
}
