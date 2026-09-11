package io.casehub.engine.flow.quarkus;

import io.casehub.engine.common.internal.judgment.JudgmentNodeExecutor;
import io.casehub.engine.common.spi.EventLogRepository;
import io.casehub.engine.common.spi.WorkOrchestrator;
import io.casehub.engine.common.spi.cache.CaseInstanceCache;
import io.casehub.engine.flow.CallableDispatchRegistry;
import io.casehub.engine.flow.CasehubDispatch;
import io.casehub.engine.flow.CasehubJudgment;
import io.casehub.engine.flow.FlowExecutionRegistry;
import io.casehub.engine.flow.FlowWorkerFunctionHandler;
import io.casehub.engine.flow.FlowWorkerFunctionProvider;
import io.quarkus.virtual.threads.VirtualThreads;
import io.serverlessworkflow.impl.WorkflowApplication;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import java.util.concurrent.ExecutorService;

@ApplicationScoped
public class FlowBeans {

  @Produces
  @ApplicationScoped
  CallableDispatchRegistry callableDispatchRegistry() {
    return new CallableDispatchRegistry();
  }

  @Produces
  @ApplicationScoped
  FlowExecutionRegistry flowExecutionRegistry() {
    return new FlowExecutionRegistry();
  }

  @Produces
  @ApplicationScoped
  FlowWorkerFunctionProvider flowWorkerFunctionProvider() {
    return new FlowWorkerFunctionProvider();
  }

  @Produces
  @ApplicationScoped
  FlowWorkerFunctionHandler flowWorkerFunctionHandler(
      WorkflowApplication app,
      FlowExecutionRegistry registry,
      @VirtualThreads ExecutorService virtualThreads) {
    return new FlowWorkerFunctionHandler(app, registry, virtualThreads);
  }

  @Produces
  @ApplicationScoped
  CasehubDispatch casehubDispatch(
      FlowExecutionRegistry registry,
      WorkOrchestrator orchestrator,
      EventLogRepository eventLogRepository,
      CaseInstanceCache caseInstanceCache,
      CallableDispatchRegistry dispatchRegistry) {
    CasehubDispatch dispatch =
        new CasehubDispatch(
            registry, orchestrator, eventLogRepository, caseInstanceCache, dispatchRegistry);
    dispatch.register();
    return dispatch;
  }

  @Produces
  @ApplicationScoped
  CasehubJudgment casehubJudgment(
      FlowExecutionRegistry executionRegistry,
      CallableDispatchRegistry dispatchRegistry,
      JudgmentNodeExecutor judgmentNodeExecutor,
      CaseInstanceCache caseInstanceCache) {
    CasehubJudgment judgment =
        new CasehubJudgment(
            executionRegistry,
            dispatchRegistry,
            judgmentNodeExecutor,
            caseInstanceCache,
            java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor());
    judgment.register();
    return judgment;
  }
}
