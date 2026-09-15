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

import io.casehub.api.view.EventLogPage;
import io.casehub.platform.api.mcp.McpDomain;
import io.casehub.platform.api.mcp.PaginatedResponse;
import io.casehub.platform.api.mcp.PathParam;
import io.casehub.platform.api.mcp.PlatformQuery;
import java.util.List;
import java.util.UUID;

@McpDomain("engine/events")
public interface EngineEventLogApi {

  @PlatformQuery("Get paginated and filtered event log for a case")
  @PaginatedResponse
  EventLogPage getEventLog(
      @PathParam UUID caseId,
      String tenancyId,
      Integer offset,
      Integer limit,
      List<String> eventTypes,
      List<String> streamTypes);
}
