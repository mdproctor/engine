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

import io.casehub.platform.api.routing.NamedStrategy;
import java.util.Map;
import java.util.Set;

/**
 * Provides operational workload snapshots for candidate users. Used by {@code
 * ConstraintHumanTaskRoutingStrategy} for per-candidate workload-based filtering and scoring.
 *
 * <p>The default implementation ({@code NoOpWorkloadDataProvider}) returns an empty map, causing
 * workload constraints to degrade gracefully. Real implementations may be backed by {@code
 * WorkerExecutionManager.getActiveCaseIds()} (engine-actor-state) or WorkItem query
 * (work-engine-adapter).
 */
public interface WorkloadDataProvider extends NamedStrategy {
  Map<String, WorkloadSnapshot> getWorkload(Set<String> userIds, String tenancyId);
}
