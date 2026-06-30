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
package io.casehub.actorstate;

import io.casehub.api.model.CaseChannel;
import io.casehub.platform.api.actor.ActorStateAccumulator;
import io.casehub.platform.api.actor.ActorStateContributor;
import io.casehub.qhorus.api.store.CommitmentStore;
import io.casehub.qhorus.runtime.store.ChannelStore;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/** Contributes open Commitments from casehub-qhorus. Batch-loads channels to avoid N+1. */
@ApplicationScoped
public class QhorusActorStateContributor implements ActorStateContributor {

  @Inject CommitmentStore commitmentStore;

  @Inject ChannelStore channelStore;

  /** Test constructor. */
  QhorusActorStateContributor(
      final CommitmentStore commitmentStore, final ChannelStore channelStore) {
    this.commitmentStore = commitmentStore;
    this.channelStore = channelStore;
  }

  /** CDI constructor. */
  QhorusActorStateContributor() {}

  @Override
  public String sourceName() {
    return "qhorus";
  }

  @Override
  public void contribute(final String actorId, final ActorStateAccumulator acc) {
    // Atomic: collect all data before calling accumulator.
    final var open = commitmentStore.findOpenByObligor(actorId);
    final Set<UUID> channelIds = open.stream().map(c -> c.channelId).collect(Collectors.toSet());
    // Batch channel lookup — one IN(?) query via findByIds instead of N queries.
    final Map<UUID, String> channelNames =
        channelStore.findByIds(channelIds).stream()
            .collect(Collectors.toMap(ch -> ch.id, ch -> ch.name));
    open.forEach(
        c ->
            acc.commitment(
                c.id,
                c.channelId,
                CaseChannel.parseCaseId(channelNames.get(c.channelId)),
                c.state.name(),
                c.expiresAt));
  }
}
