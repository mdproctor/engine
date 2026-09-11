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
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Any;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import org.eclipse.microprofile.context.ManagedExecutor;
import org.jboss.logging.Logger;

/**
 * Assembles actor state from all registered {@link ActorStateContributor} beans in parallel.
 *
 * <p>Uses {@link ManagedExecutor} (MicroProfile Context Propagation) to propagate CDI request
 * context and Hibernate session across threads — required for Panache-backed stores.
 *
 * <p>Each contributor is independent: a contributor failure excludes that source from {@code
 * sources} and adds a warning to {@code sourceWarnings} without affecting others.
 *
 * <p>Active only when {@code casehub.qhorus.reactive.enabled} is false or absent (default).
 */
@ApplicationScoped
@io.quarkus.arc.properties.UnlessBuildProperty(
    name = "casehub.qhorus.reactive.enabled",
    stringValue = "true",
    enableIfMissing = true)
public class ActorStateAggregator {

  private static final Logger LOG = Logger.getLogger(ActorStateAggregator.class);

  private final List<ActorStateContributor> contributors;
  private final ManagedExecutor executor;

  /** CDI constructor — injects all ActorStateContributor beans and ManagedExecutor. */
  @Inject
  public ActorStateAggregator(
      @Any final Instance<ActorStateContributor> contributors, final ManagedExecutor executor) {
    this.contributors = contributors.stream().toList();
    this.executor = executor;
  }

  /** Test constructor — accepts an explicit contributor list; uses sequential execution. */
  ActorStateAggregator(final List<ActorStateContributor> contributors) {
    this.contributors = contributors;
    this.executor = null;
  }

  /**
   * Assembles actor state from all contributors.
   *
   * @param actorId the actor identity string (must be consistent across all backends)
   * @return assembled response; always returns 200 even when some sources fail
   */
  public ActorStateResponse forActor(final String actorId) {
    final ActorStateAccumulatorImpl accumulator = new ActorStateAccumulatorImpl(actorId);

    if (executor != null) {
      // CDI production path — parallel via ManagedExecutor
      final List<CompletableFuture<Void>> futures = new ArrayList<>();
      for (final ActorStateContributor c : contributors) {
        futures.add(
            CompletableFuture.runAsync(() -> runContributor(c, actorId, accumulator), executor));
      }
      CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
    } else {
      // Test path — sequential (no ManagedExecutor in plain JUnit)
      contributors.forEach(c -> runContributor(c, actorId, accumulator));
    }

    return accumulator.build();
  }

  private void runContributor(
      final ActorStateContributor c,
      final String actorId,
      final ActorStateAccumulatorImpl accumulator) {
    try {
      c.contribute(actorId, accumulator);
      accumulator.markSucceeded(c.sourceName());
    } catch (final Exception e) {
      LOG.warnf("source %s failed for actorId=%s: %s", c.sourceName(), actorId, e.getMessage());
      // e.getMessage() may be null for messageless exceptions (e.g. NullPointerException()).
      // ConcurrentHashMap rejects null values — guard to preserve the "always return 200" contract.
      final String reason = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
      accumulator.markFailed(c.sourceName(), reason);
    }
  }
}
