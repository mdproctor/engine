package io.casehub.actorstate.quarkus;

import io.casehub.actorstate.ActorStateAggregator;
import io.casehub.actorstate.ActorStateResource;
import io.casehub.actorstate.EngineActorStateContributor;
import io.casehub.actorstate.LedgerActorStateContributor;
import io.casehub.actorstate.QhorusActorStateContributor;
import io.casehub.actorstate.WorkActorStateContributor;
import io.casehub.engine.common.spi.scheduler.WorkerExecutionManager;
import io.casehub.ledger.runtime.service.TrustGateService;
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
  EngineActorStateContributor engineActorStateContributor(
      WorkerExecutionManager executionManager) {
    return new EngineActorStateContributor(executionManager);
  }

  @Produces
  @ApplicationScoped
  LedgerActorStateContributor ledgerActorStateContributor(TrustGateService trustGateService) {
    return new LedgerActorStateContributor(trustGateService);
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
