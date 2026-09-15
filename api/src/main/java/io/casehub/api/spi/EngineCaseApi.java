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

import io.casehub.api.model.CaseStatus;
import io.casehub.api.view.CaseContextChangeEventView;
import io.casehub.api.view.CaseInstanceView;
import io.casehub.api.view.CaseLifecycleEventView;
import io.casehub.api.view.CasePage;
import io.casehub.api.view.CaseStreamEventView;
import io.casehub.api.view.GoalEvaluationView;
import io.casehub.api.view.PlanItemView;
import io.casehub.api.view.StartCaseRequest;
import io.casehub.platform.api.mcp.McpDomain;
import io.casehub.platform.api.mcp.PaginatedResponse;
import io.casehub.platform.api.mcp.PathParam;
import io.casehub.platform.api.mcp.PlatformMutation;
import io.casehub.platform.api.mcp.PlatformQuery;
import io.casehub.platform.api.mcp.PlatformStream;
import io.casehub.platform.api.mcp.RestStatus;
import io.smallrye.mutiny.Multi;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@McpDomain("engine/cases")
public interface EngineCaseApi {

  @PlatformQuery("List case instances with optional filtering")
  @PaginatedResponse
  CasePage listCases(
      CaseStatus status,
      String namespace,
      String name,
      String tenancyId,
      Integer offset,
      Integer limit);

  @PlatformQuery("Get a case instance by ID")
  CaseInstanceView getCaseById(@PathParam UUID caseId, String tenancyId);

  @PlatformMutation("Start a new case instance")
  @RestStatus(201)
  CaseInstanceView startCase(StartCaseRequest request, String tenancyId);

  @PlatformQuery("Get full case context as JSON")
  Map<String, Object> getCaseContext(@PathParam UUID caseId, String tenancyId);

  @PlatformQuery("Get case context at a specific path")
  Map<String, Object> getCaseContextPath(@PathParam UUID caseId, String path, String tenancyId);

  @PlatformQuery("Get plan items for a case")
  List<PlanItemView> getPlanItems(@PathParam UUID caseId, String tenancyId);

  @PlatformQuery("Evaluate goals against live case context")
  GoalEvaluationView getGoals(@PathParam UUID caseId, String tenancyId);

  @PlatformStream("Live case event stream")
  Multi<CaseStreamEventView> caseStream(@PathParam UUID caseId);

  @PlatformStream("Live case lifecycle events")
  Multi<CaseLifecycleEventView> caseLifecycle(@PathParam UUID caseId);

  @PlatformStream("Live case context change events")
  Multi<CaseContextChangeEventView> caseContextChange(@PathParam UUID caseId);
}
