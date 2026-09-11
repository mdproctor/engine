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
package io.casehub.engine.flow;

import io.casehub.api.model.JudgmentTarget;
import io.casehub.engine.common.internal.judgment.JudgmentNodeExecutor;
import io.casehub.engine.common.spi.JudgmentResponse;
import io.casehub.engine.common.spi.JudgmentScheduleRequest;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import org.jboss.logging.Logger;

@ApplicationScoped
public class CasehubJudgment {

  private static final Logger LOG = Logger.getLogger(CasehubJudgment.class);
  private static final Duration DEFAULT_TIMEOUT = Duration.ofHours(4);

  @Inject FlowExecutionRegistry executionRegistry;
  @Inject CallableDispatchRegistry dispatchRegistry;
  @Inject JudgmentNodeExecutor judgmentNodeExecutor;
  @Inject io.casehub.engine.common.spi.cache.CaseInstanceCache caseInstanceCache;

  private final java.util.concurrent.ExecutorService virtualThreads =
      java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor();

  @PostConstruct
  void register() {
    dispatchRegistry.register("casehub:judgment", this::dispatch);
  }

  @SuppressWarnings("unchecked")
  CompletableFuture<Map<String, Object>> dispatch(
      String workflowInstanceId, Map<String, Object> args) {
    String prompt = (String) args.get("prompt");
    if (prompt == null) {
      throw new IllegalArgumentException(
          "casehub:judgment step is missing required 'prompt' argument");
    }
    String bindingName = (String) args.get("binding");
    if (bindingName == null) {
      throw new IllegalArgumentException(
          "casehub:judgment step is missing required 'binding' argument");
    }

    FlowExecution execution = executionRegistry.get(workflowInstanceId);
    io.casehub.engine.common.internal.model.CaseInstance instance =
        caseInstanceCache.get(execution.caseId());
    if (instance == null) {
      throw new IllegalStateException(
          "CaseInstance not found for caseId="
              + execution.caseId()
              + " — cannot dispatch casehub:judgment");
    }

    Duration timeout =
        args.containsKey("timeoutSeconds")
            ? Duration.ofSeconds(((Number) args.get("timeoutSeconds")).longValue())
            : DEFAULT_TIMEOUT;

    JudgmentTarget target = JudgmentTarget.builder().prompt(prompt).build();

    Map<String, Object> inputData =
        args.containsKey("input") ? (Map<String, Object>) args.get("input") : Map.of();

    JudgmentScheduleRequest request =
        new JudgmentScheduleRequest(
            execution.caseId(), instance.tenancyId, bindingName, target, inputData, null, null);

    return CompletableFuture.supplyAsync(
        () -> {
          JudgmentResponse response = judgmentNodeExecutor.execute(request, timeout);
          return Map.of(
              "decision", (Object) response.decision(),
              "evidence", (Object) response.evidence());
        },
        virtualThreads);
  }
}
