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

/**
 * Holds the engine-side context for an in-flight workflow execution. Keyed by the workflow instance
 * ID in {@link FlowExecutionRegistry} so {@link CasehubDispatch} can emit correct lineage events
 * when a workflow step dispatches a capability.
 */
public record FlowExecution(CaseInstance caseInstance, String workerName, String inputDataHash) {}
