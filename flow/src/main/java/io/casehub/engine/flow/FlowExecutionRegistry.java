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

import io.casehub.engine.common.internal.model.CaseInstance;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Thread-safe registry mapping live workflow instance IDs to their {@link FlowExecution} context.
 * Workflow steps run on quarkus-flow's cached thread pool — thread-locals are not usable; a
 * ConcurrentHashMap keyed by instance ID is the correct cross-thread mechanism.
 */
@ApplicationScoped
public class FlowExecutionRegistry {

  private final ConcurrentHashMap<String, FlowExecution> executions = new ConcurrentHashMap<>();

  public void register(
      final String instanceId,
      final CaseInstance caseInstance,
      final String workerName,
      final String inputDataHash) {
    executions.put(instanceId, new FlowExecution(caseInstance, workerName, inputDataHash));
  }

  public FlowExecution get(final String instanceId) {
    final FlowExecution execution = executions.get(instanceId);
    if (execution == null) {
      throw new IllegalStateException(
          "No FlowExecution registered for workflow instance ID: "
              + instanceId
              + ". Dispatch called outside a workflow step or after cleanup.");
    }
    return execution;
  }

  public void remove(final String instanceId) {
    executions.remove(instanceId);
  }
}
