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
package io.casehub.engine.actorstate.quarkus;

import io.casehub.engine.actorstate.ActorStateAggregator;
import io.casehub.engine.actorstate.ActorStateResource;
import io.casehub.engine.actorstate.EngineActorStateContributor;
import io.casehub.engine.actorstate.LedgerActorStateContributor;
import io.casehub.engine.actorstate.QhorusActorStateContributor;
import io.casehub.engine.actorstate.WorkActorStateContributor;
import io.casehub.engine.common.spi.scheduler.WorkerExecutionManager;
import io.casehub.ledger.api.spi.TrustScoreSource;
import io.casehub.platform.api.actor.ActorStateContributor;
import io.casehub.qhorus.api.store.ChannelStore;
import io.casehub.qhorus.api.store.CommitmentStore;
import io.casehub.work.api.spi.WorkItemStore;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Any;
import jakarta.enterprise.inject.Instance;
import jakarta.enterprise.inject.Produces;
import org.eclipse.microprofile.context.ManagedExecutor;

@ApplicationScoped
public class ActorStateBeans {

  @Produces
  @ApplicationScoped
  ActorStateAggregator actorStateAggregator(
      @Any Instance<ActorStateContributor> contributors, ManagedExecutor executor) {
    return new ActorStateAggregator(contributors.stream().toList(), executor);
  }

  @Produces
  @ApplicationScoped
  ActorStateResource actorStateResource(ActorStateAggregator aggregator) {
    return new ActorStateResource(aggregator);
  }

  @Produces
  @ApplicationScoped
  EngineActorStateContributor engineActorStateContributor(WorkerExecutionManager executionManager) {
    return new EngineActorStateContributor(executionManager);
  }

  @Produces
  @ApplicationScoped
  LedgerActorStateContributor ledgerActorStateContributor(TrustScoreSource trustScoreSource) {
    return new LedgerActorStateContributor(trustScoreSource);
  }

  @Produces
  @ApplicationScoped
  WorkActorStateContributor workActorStateContributor(WorkItemStore workItemStore) {
    return new WorkActorStateContributor(workItemStore);
  }

  @Produces
  @ApplicationScoped
  QhorusActorStateContributor qhorusActorStateContributor(
      CommitmentStore commitmentStore, ChannelStore channelStore) {
    return new QhorusActorStateContributor(commitmentStore, channelStore);
  }
}
