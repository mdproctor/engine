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
package io.casehub.api.spi;

import io.casehub.engine.plan.snapshot.DagPlanSnapshot;
import io.casehub.engine.plan.snapshot.DecompositionSnapshot;
import io.casehub.engine.plan.snapshot.PlanItemDefinitionSnapshot;
import io.casehub.platform.api.mcp.McpDomain;
import io.casehub.platform.api.mcp.PathParam;
import io.casehub.platform.api.mcp.PlatformQuery;
import io.casehub.platform.api.mcp.PlatformStream;
import io.smallrye.mutiny.Multi;
import java.util.List;
import java.util.UUID;

@McpDomain("engine/plan")
public interface EnginePlanApi {

  @PlatformQuery("Get live case plan model snapshot")
  Object getPlanModel(@PathParam UUID caseId, String tenancyId);

  @PlatformQuery("Get plan item definition hierarchy")
  List<PlanItemDefinitionSnapshot> getPlanDefinitions(@PathParam UUID caseId, String tenancyId);

  @PlatformQuery("Get HTN decomposition tree snapshot")
  DecompositionSnapshot getDecomposition(@PathParam UUID caseId, String tenancyId);

  @PlatformQuery("Get DAG plan snapshot")
  DagPlanSnapshot getDagPlan(@PathParam UUID caseId, String tenancyId);

  @PlatformQuery("Get DAG execution result snapshot")
  Object getDagResult(@PathParam UUID caseId, String tenancyId);

  @PlatformQuery("Get composed execution state snapshot")
  Object getExecutionState(@PathParam UUID caseId, String tenancyId);

  @PlatformStream("Live execution state updates")
  Multi<Object> executionStateStream(@PathParam UUID caseId);
}
