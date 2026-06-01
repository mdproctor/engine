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

import io.casehub.platform.api.actor.ActorStateContributor;
import io.quarkus.arc.properties.IfBuildProperty;
import io.smallrye.mutiny.Uni;
import io.smallrye.mutiny.infrastructure.Infrastructure;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Any;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import java.util.List;
import org.jboss.logging.Logger;

/**
 * Reactive variant — active when {@code casehub.qhorus.reactive.enabled=true}.
 *
 * <p>Contributors are always blocking ({@code void contribute()}). This aggregator wraps each on
 * Mutiny's blocking executor — no reactive contributor interface needed, no parity problem.
 *
 * <p>Note: {@code casehub.qhorus.reactive.enabled=true} is additive — {@code JpaCommitmentStore}
 * (blocking) remains available alongside {@code ReactiveJpaCommitmentStore}. All four contributors
 * work in both reactive and non-reactive modes.
 */
@IfBuildProperty(name = "casehub.qhorus.reactive.enabled", stringValue = "true")
@ApplicationScoped
public class ReactiveActorStateAggregator {

  private static final Logger LOG = Logger.getLogger(ReactiveActorStateAggregator.class);

  private final List<ActorStateContributor> contributors;

  @Inject
  public ReactiveActorStateAggregator(@Any final Instance<ActorStateContributor> contributors) {
    this.contributors = contributors.stream().toList();
  }

  /** Test constructor. */
  ReactiveActorStateAggregator(final List<ActorStateContributor> contributors) {
    this.contributors = contributors;
  }

  public Uni<ActorStateResponse> forActor(final String actorId) {
    final ActorStateAccumulatorImpl accumulator = new ActorStateAccumulatorImpl(actorId);
    final List<Uni<Void>> unis =
        contributors.stream()
            .<Uni<Void>>map(
                c ->
                    Uni.createFrom()
                        .<Void>voidItem()
                        .invoke(
                            () -> {
                              try {
                                c.contribute(actorId, accumulator);
                                accumulator.markSucceeded(c.sourceName());
                              } catch (final Exception e) {
                                LOG.warnf(
                                    "source %s failed for actorId=%s: %s",
                                    c.sourceName(), actorId, e.getMessage());
                                // e.getMessage() may be null — ConcurrentHashMap rejects null
                                // values
                                final String reason =
                                    e.getMessage() != null
                                        ? e.getMessage()
                                        : e.getClass().getSimpleName();
                                accumulator.markFailed(c.sourceName(), reason);
                              }
                            })
                        .runSubscriptionOn(Infrastructure.getDefaultExecutor()))
            .toList();
    return Uni.combine().all().unis(unis).discardItems().map(v -> accumulator.build());
  }
}
