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
import io.casehub.api.spi.ReactiveCaseChannelProvider;
import io.casehub.qhorus.api.message.MessageType;
import io.quarkus.arc.DefaultBean;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Reactive no-op CaseChannelProvider. Returns sentinel channels with {@code backendType = "none"}.
 * All write operations complete with no side-effects. Active by default — replace with a real
 * reactive implementation when a channel backend is needed.
 */
@DefaultBean
@ApplicationScoped
public class NoOpReactiveCaseChannelProvider implements ReactiveCaseChannelProvider {

  @Override
  public Uni<CaseChannel> openChannel(UUID caseId, String purpose) {
    return Uni.createFrom()
        .item(new CaseChannel(caseId + "/" + purpose, purpose, purpose, "none", Map.of()));
  }

  @Override
  public Uni<Void> postToChannel(
      CaseChannel channel,
      String from,
      String content,
      MessageType type,
      String correlationId,
      String deadline, String target) {
    return Uni.createFrom().voidItem();
  }

  @Override
  public Uni<Void> closeChannel(CaseChannel channel) {
    return Uni.createFrom().voidItem();
  }

  @Override
  public Uni<List<CaseChannel>> listChannels(UUID caseId) {
    return Uni.createFrom().item(List.of());
  }
}
