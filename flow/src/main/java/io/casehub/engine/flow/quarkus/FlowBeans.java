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
