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

import io.quarkus.arc.Arc;
import io.serverlessworkflow.api.types.CallFunction;
import io.serverlessworkflow.api.types.TaskBase;
import io.serverlessworkflow.impl.WorkflowDefinition;
import io.serverlessworkflow.impl.WorkflowMutablePosition;
import io.serverlessworkflow.impl.executors.CallableTask;
import io.serverlessworkflow.impl.executors.CallableTaskBuilder;
import java.util.Map;

/**
 * Handles {@code call: casehub:dispatch} steps in Serverless Workflow YAML. Registered via {@code
 * META-INF/services/io.serverlessworkflow.impl.executors.CallableTaskBuilder}; discovered by {@code
 * DefaultTaskExecutorFactory}'s ServiceLoader when routing {@code CallFunction} tasks.
 *
 * <p>In 7.13.4.Final, {@code call: casehub:dispatch} in YAML is parsed as a {@code CallFunction}
 * with {@code call = "casehub:dispatch"} and {@code with = FunctionArguments}. This builder handles
 * all {@code CallFunction} instances; unknown call names fail fast in {@code init()}.
 */
public class CasehubCallableTaskBuilder implements CallableTaskBuilder<CallFunction> {

  // ServiceLoader caches a single instance of this builder. init() and build() are always called
  // sequentially on the same thread (per DefaultTaskExecutorFactory), but may be called
  // concurrently from different threads loading different workflow definitions. ThreadLocal
  // passes capability between init() and build() without shared mutable state.
  private final ThreadLocal<String> capabilityHolder = new ThreadLocal<>();

  @Override
  public boolean accept(final Class<? extends TaskBase> clazz) {
    return CallFunction.class.isAssignableFrom(clazz);
  }

  @Override
  public void init(
      final CallFunction task,
      final WorkflowDefinition definition,
      final WorkflowMutablePosition position) {
    if (!"casehub:dispatch".equals(task.getCall())) {
      throw new UnsupportedOperationException(
          "CasehubCallableTaskBuilder only handles 'casehub:dispatch', got: " + task.getCall());
    }
    final Map<String, Object> args =
        task.getWith() != null ? task.getWith().getAdditionalProperties() : null;
    if (args == null || args.get("capability") == null) {
      throw new IllegalArgumentException(
          "casehub:dispatch step is missing required 'capability' argument");
    }
    capabilityHolder.set(args.get("capability").toString());
  }

  @Override
  public CallableTask build() {
    final String cap = capabilityHolder.get();
    capabilityHolder.remove(); // prevent ThreadLocal leak
    // CallableTask.apply() returns CompletableFuture<WorkflowModel> — fully async, no blocking.
    return (workflowContext, taskContext, input) -> {
      final String instanceId = workflowContext.instanceData().id();
      return Arc.container()
          .instance(CasehubDispatch.class)
          .get()
          .dispatch(instanceId, cap)
          .thenApply(
              output -> workflowContext.definition().application().modelFactory().from(output));
    };
  }
}
