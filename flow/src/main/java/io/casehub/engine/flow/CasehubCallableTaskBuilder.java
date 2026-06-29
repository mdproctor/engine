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
 * Routes {@code call:} steps in Serverless Workflow YAML to {@link CallableDispatcher}
 * implementations registered in {@link CallableDispatchRegistry}. Registered via {@code
 * META-INF/services/io.serverlessworkflow.impl.executors.CallableTaskBuilder}; discovered by {@code
 * DefaultTaskExecutorFactory}'s ServiceLoader when routing {@code CallFunction} tasks.
 *
 * <p>Accepts all {@code CallFunction} instances. Unknown call names are not validated in {@code
 * init()} (CDI is unavailable during ServiceLoader instantiation) — they fail at dispatch time with
 * a clear error from {@link CallableDispatchRegistry#get(String)}.
 */
public class CasehubCallableTaskBuilder implements CallableTaskBuilder<CallFunction> {

  // ServiceLoader caches a single instance of this builder. init() and build() are always called
  // sequentially on the same thread (per DefaultTaskExecutorFactory), but may be called
  // concurrently from different threads loading different workflow definitions. ThreadLocal
  // passes state between init() and build() without shared mutable state.
  private final ThreadLocal<String> callNameHolder = new ThreadLocal<>();
  private final ThreadLocal<Map<String, Object>> argsHolder = new ThreadLocal<>();

  @Override
  public boolean accept(final Class<? extends TaskBase> clazz) {
    return CallFunction.class.isAssignableFrom(clazz);
  }

  @Override
  public void init(
      final CallFunction task,
      final WorkflowDefinition definition,
      final WorkflowMutablePosition position) {
    callNameHolder.set(task.getCall());
    final Map<String, Object> args =
        task.getWith() != null ? task.getWith().getAdditionalProperties() : Map.of();
    argsHolder.set(args);
  }

  @Override
  public CallableTask build() {
    final String callName = callNameHolder.get();
    final Map<String, Object> args = argsHolder.get();
    callNameHolder.remove();
    argsHolder.remove();
    return (workflowContext, taskContext, input) -> {
      final String instanceId = workflowContext.instanceData().id();
      return Arc.container()
          .instance(CallableDispatchRegistry.class)
          .get()
          .get(callName)
          .dispatch(instanceId, args)
          .thenApply(
              output -> workflowContext.definition().application().modelFactory().from(output));
    };
  }
}
