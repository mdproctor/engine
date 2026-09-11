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
package io.casehub.engine.common.spi;

import io.casehub.api.model.WorkRequest;
import io.casehub.api.model.WorkResult;
import io.casehub.engine.common.internal.model.CaseInstance;
import java.util.concurrent.CompletionStage;

/**
 * Submits work requests to the CaseHub engine and returns a future that resolves when the work
 * completes. Implementations route the request to the appropriate worker via {@link
 * io.casehub.api.spi.routing.AgentRoutingStrategy}.
 *
 * <p>The default implementation ({@code DefaultWorkOrchestrator} in the engine runtime) is resolved
 * via CDI. {@code casehub-engine-flow} injects this interface to dispatch workers from within
 * workflow steps without taking a compile-time dependency on the runtime module.
 */
public interface WorkOrchestrator {

  /**
   * Submit work and return a future that completes when the worker finishes. Does not change the
   * case status — the case continues running while the work executes.
   */
  CompletionStage<WorkResult> submit(CaseInstance instance, WorkRequest request);

  /**
   * Submit work and suspend the case to {@link io.casehub.api.model.CaseStatus#WAITING} until the
   * work completes. Persists {@code waitingForWorkId} for JVM-restart durability.
   */
  CompletionStage<WorkResult> submitAndWait(CaseInstance instance, WorkRequest request);
}
