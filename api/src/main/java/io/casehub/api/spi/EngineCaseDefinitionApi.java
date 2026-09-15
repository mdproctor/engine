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

import io.casehub.api.view.CaseDefinitionPage;
import io.casehub.api.view.CaseDefinitionView;
import io.casehub.platform.api.mcp.McpDomain;
import io.casehub.platform.api.mcp.PaginatedResponse;
import io.casehub.platform.api.mcp.PathParam;
import io.casehub.platform.api.mcp.PlatformQuery;
import java.util.List;

@McpDomain("engine/definitions")
public interface EngineCaseDefinitionApi {

  @PlatformQuery("List registered case definitions")
  @PaginatedResponse
  CaseDefinitionPage listDefinitions(String tenancyId, Integer offset, Integer limit);

  @PlatformQuery("Get definitions by namespace and name")
  List<CaseDefinitionView> getDefinitionsByName(
      @PathParam String namespace, @PathParam String name, String tenancyId);

  @PlatformQuery("Get a specific definition by namespace, name, and version")
  CaseDefinitionView getDefinitionByKey(
      @PathParam String namespace,
      @PathParam String name,
      @PathParam String version,
      String tenancyId);
}
