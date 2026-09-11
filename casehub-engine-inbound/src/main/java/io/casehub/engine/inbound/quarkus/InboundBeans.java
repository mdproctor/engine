package io.casehub.engine.inbound.quarkus;

import io.casehub.api.engine.CaseHubRuntime;
import io.casehub.engine.common.internal.context.BridgeResolver;
import io.casehub.engine.common.internal.jq.JQEvaluator;
import io.casehub.engine.common.spi.CaseDefinitionRegistry;
import io.casehub.engine.inbound.InboundSignalBridge;
import io.casehub.engine.inbound.InboundWorkItemBridge;
import io.casehub.engine.inbound.InboundWorkItemPolicy;
import io.casehub.platform.api.routing.StrategyResolver;
import io.casehub.work.api.spi.TenantContextExecutor;
import io.casehub.work.api.spi.WorkItemOperations;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.enterprise.inject.Produces;
import java.util.Optional;

@ApplicationScoped
public class InboundBeans {

  @Produces
  @ApplicationScoped
  InboundSignalBridge inboundSignalBridge(
      Instance<CaseDefinitionRegistry> registry,
      Instance<CaseHubRuntime> runtime,
      BridgeResolver bridgeResolver,
      StrategyResolver strategyResolver,
      JQEvaluator jqEvaluator) {
    return new InboundSignalBridge(
        registry.isResolvable() ? Optional.of(registry.get()) : Optional.empty(),
        runtime.isResolvable() ? Optional.of(runtime.get()) : Optional.empty(),
        bridgeResolver,
        strategyResolver,
        jqEvaluator);
  }

  @Produces
  @ApplicationScoped
  InboundWorkItemBridge inboundWorkItemBridge(
      Instance<InboundWorkItemPolicy> policy,
      WorkItemOperations workItemOperations,
      TenantContextExecutor tenantContextExecutor) {
    return new InboundWorkItemBridge(
        policy.isResolvable() ? Optional.of(policy.get()) : Optional.empty(),
        workItemOperations,
        tenantContextExecutor);
  }
}
