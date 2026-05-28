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
package io.casehub.api.spi.routing;

import io.casehub.eidos.api.AgentDescriptor;
import java.util.Set;

/**
 * A pre-filtered, pre-probed agent worker candidate passed to {@link AgentRoutingStrategy#select}.
 *
 * <p>{@code runningJobs} is the count of active Quartz jobs for this worker, sourced from {@code
 * WorkerExecutionManager} — not WorkItem counts. This correctly represents agent load.
 *
 * <p>{@code capabilities} is the worker's full declared capability set, not just the one being
 * matched.
 *
 * <p>{@code agentDescriptor} carries the agent's full vocabulary (domain, slot, disposition,
 * capability descriptions) for semantic routing strategies. Nullable — strategies that receive a
 * null descriptor must treat the candidate as bootstrap (availability routing only).
 *
 * @param workerId the worker name from the case definition YAML
 * @param capabilities all capabilities declared by this worker
 * @param runningJobs count of currently active Quartz execution jobs for this worker
 * @param health pre-probed health status; UNAVAILABLE workers are never included
 * @param agentDescriptor the agent's registered descriptor from casehub-eidos; null if no
 *     descriptor is registered for this worker
 */
public record AgentCandidate(
    String workerId,
    Set<String> capabilities,
    int runningJobs,
    AgentHealth health,
    AgentDescriptor agentDescriptor) {}
