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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import io.casehub.qhorus.api.channel.Channel;
import io.casehub.qhorus.api.message.Commitment;
import io.casehub.qhorus.api.message.CommitmentState;
import io.casehub.qhorus.api.store.ChannelStore;
import io.casehub.qhorus.api.store.CommitmentStore;
import io.casehub.qhorus.api.store.query.ChannelQuery;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Plain JUnit tests for QhorusActorStateContributor — no CDI, uses test constructor. */
class QhorusActorStateContributorTest {

  // ── Gap 2: deleted-channel race ───────────────────────────────────────────

  @Test
  void deletedChannel_caseIdNull_noException() {
    // Race: channel deleted between commitmentStore.findOpenByObligor and channelStore.findByIds.
    // channelNames.get(c.channelId) returns null → CaseChannel.parseCaseId(null) returns null.
    // The commitment must still be contributed (no NPE), with caseId=null.
    final UUID channelId = UUID.randomUUID();

    final Commitment commitment =
        Commitment.builder()
            .id(UUID.randomUUID())
            .channelId(channelId)
            .obligor("agent-x")
            .state(CommitmentState.OPEN)
            .expiresAt(Instant.now().plusSeconds(3600))
            .build();

    final CommitmentStore commitmentStore = openCommitmentsOnly(List.of(commitment));
    final ChannelStore channelStore = alwaysEmpty();

    final var acc = new ActorStateAccumulatorImpl("agent-x");
    new QhorusActorStateContributor(commitmentStore, channelStore).contribute("agent-x", acc);
    acc.markSucceeded("qhorus");

    final var resp = acc.build();
    assertEquals(1, resp.openCommitments().size());
    assertNull(resp.openCommitments().get(0).caseId());
  }

  // ── Store stubs ───────────────────────────────────────────────────────────

  /** CommitmentStore that returns the given list from findOpenByObligor; all else unsupported. */
  private static CommitmentStore openCommitmentsOnly(final List<Commitment> open) {
    return new CommitmentStore() {
      @Override
      public List<Commitment> findAllOpen() {
        return open;
      }

      @Override
      public Commitment save(final Commitment c) {
        throw new UnsupportedOperationException();
      }

      @Override
      public Optional<Commitment> findById(final UUID id) {
        throw new UnsupportedOperationException();
      }

      @Override
      public Optional<Commitment> findByCorrelationId(final String correlationId) {
        throw new UnsupportedOperationException();
      }

      @Override
      public List<Commitment> findByIds(final Collection<UUID> ids) {
        throw new UnsupportedOperationException();
      }

      @Override
      public List<Commitment> findOpenByObligor(final String obligor, final UUID channelId) {
        throw new UnsupportedOperationException();
      }

      @Override
      public List<Commitment> findOpenByRequester(final String requester, final UUID channelId) {
        throw new UnsupportedOperationException();
      }

      @Override
      public List<Commitment> findByState(final CommitmentState state, final UUID channelId) {
        throw new UnsupportedOperationException();
      }

      @Override
      public List<Commitment> findExpiredBefore(final Instant cutoff) {
        throw new UnsupportedOperationException();
      }

      @Override
      public void deleteById(final UUID id) {
        throw new UnsupportedOperationException();
      }

      @Override
      public long deleteAll(final UUID channelId) {
        throw new UnsupportedOperationException();
      }

      @Override
      public long deleteExpiredBefore(final Instant cutoff) {
        throw new UnsupportedOperationException();
      }

      @Override
      public List<Commitment> findByChannel(final UUID channelId) {
        throw new UnsupportedOperationException();
      }

      @Override
      public List<Commitment> findAllByCorrelationId(final String correlationId) {
        throw new UnsupportedOperationException();
      }

      @Override
      public List<Commitment> findOpenByChannelId(final UUID channelId) {
        throw new UnsupportedOperationException();
      }
    };
  }

  /** ChannelStore where every channel lookup returns empty — simulates post-delete state. */
  private static ChannelStore alwaysEmpty() {
    return new ChannelStore() {
      @Override
      public Optional<Channel> find(final UUID id) {
        return Optional.empty();
      }

      @Override
      public List<Channel> findByIds(final Collection<UUID> ids) {
        return List.of();
      }

      @Override
      public Channel put(final Channel channel) {
        throw new UnsupportedOperationException();
      }

      @Override
      public Optional<Channel> findByName(final String name) {
        throw new UnsupportedOperationException();
      }

      @Override
      public List<Channel> scan(final ChannelQuery query) {
        throw new UnsupportedOperationException();
      }

      @Override
      public void delete(final UUID id) {
        throw new UnsupportedOperationException();
      }

      @Override
      public void updateLastActivity(final UUID channelId, final String tenancyId) {
        throw new UnsupportedOperationException();
      }
    };
  }
}
