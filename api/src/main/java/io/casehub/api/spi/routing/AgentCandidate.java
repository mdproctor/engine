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
import io.casehub.eidos.api.MatchDegree;
import java.util.Set;

/**
 * A pre-filtered, pre-probed agent worker candidate passed to {@link AgentRoutingStrategy#select}.
 *
 * @param workerId the worker name from the case definition YAML
 * @param capabilities all capabilities declared by this worker
 * @param runningJobs count of currently active Quartz execution jobs for this worker
 * @param health pre-probed health status; UNAVAILABLE workers are never included
 * @param agentDescriptor the agent's registered descriptor from casehub-eidos; null if no
 *     descriptor is registered for this worker
 * @param matchDegree how this worker matched the requested capability; null when match metadata is
 *     unavailable (bootstrap workers without eidos descriptors)
 */
public record AgentCandidate(
    String workerId,
    Set<String> capabilities,
    int runningJobs,
    AgentHealth health,
    AgentDescriptor agentDescriptor,
    MatchDegree matchDegree) {}
