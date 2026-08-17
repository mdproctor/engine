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
package io.casehub.engine.graphql;

import io.casehub.api.engine.CaseHubRuntime;
import io.casehub.api.model.CaseDefinition;
import io.casehub.api.model.event.CaseHubEventType;
import io.casehub.engine.common.internal.model.CaseInstance;
import io.casehub.engine.common.spi.CaseDefinitionRegistry;
import io.casehub.engine.common.spi.CaseInstanceRepository;
import io.casehub.engine.common.spi.PlanItemStore;
import io.casehub.engine.common.spi.query.CaseInstanceQuery;
import io.casehub.engine.graphql.dto.CaseDefinitionPage;
import io.casehub.engine.graphql.dto.CaseDefinitionType;
import io.casehub.engine.graphql.dto.CaseFilterInput;
import io.casehub.engine.graphql.dto.CaseInstanceType;
import io.casehub.engine.graphql.dto.CasePage;
import io.casehub.engine.graphql.dto.EventLogEntry;
import io.casehub.platform.api.identity.CurrentPrincipal;
import io.casehub.platform.api.mcp.McpDomain;
import io.casehub.platform.graphql.PageInfo;
import io.casehub.platform.graphql.PageInput;
import io.casehub.platform.graphql.scalar.Json;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.eclipse.microprofile.graphql.Description;
import org.eclipse.microprofile.graphql.GraphQLApi;
import org.eclipse.microprofile.graphql.Query;

@GraphQLApi
@McpDomain("engine")
@ApplicationScoped
public class CaseQueryResolver {

  @Inject CaseInstanceRepository instanceRepository;
  @Inject CaseDefinitionRegistry definitionRegistry;
  @Inject CaseHubRuntime runtime;
  @Inject CurrentPrincipal currentPrincipal;
  @Inject PlanItemStore planItemStore;

  @Query
  @Description("List cases with optional filtering and pagination")
  public CasePage cases(CaseFilterInput filter, PageInput page) {
    int offset = page != null && page.offset() != null ? page.offset() : 0;
    int limit = page != null && page.limit() != null ? page.limit() : 20;

    var queryBuilder = CaseInstanceQuery.builder().page(offset / Math.max(1, limit)).size(limit);

    if (filter != null) {
      queryBuilder.status(filter.status()).namespace(filter.namespace()).name(filter.name());
    }

    String tenancyId = currentPrincipal.tenancyId();
    var query = queryBuilder.build();
    List<CaseInstanceType> items =
        instanceRepository.query(query, tenancyId).stream().map(CaseInstanceType::from).toList();
    long total = instanceRepository.count(query, tenancyId);

    boolean hasNext = (long) offset + limit < total;
    boolean hasPrevious = offset > 0;
    return new CasePage(items, new PageInfo(hasNext, hasPrevious, (int) total, null));
  }

  @Query
  @Description("Retrieve a single case by its unique identifier")
  public CaseInstanceType caseById(UUID caseId) {
    String tenancyId = currentPrincipal.tenancyId();
    CaseInstance instance = instanceRepository.findByUuid(caseId, tenancyId);
    return instance != null ? CaseInstanceType.from(instance) : null;
  }

  @Query
  @Description("Query case context data — full context or a specific path within it")
  public Json caseContext(UUID caseId, String path) {
    Object result;
    if (path != null && !path.isEmpty()) {
      result = runtime.query(caseId, path);
    } else {
      result = runtime.query(caseId, "/");
    }
    if (result instanceof Map<?, ?> map) {
      @SuppressWarnings("unchecked")
      Map<String, Object> typedMap = (Map<String, Object>) map;
      return Json.of(typedMap);
    }
    return result != null ? Json.of(Map.of("value", result)) : null;
  }

  @Query
  @Description("List available case definitions with pagination")
  public CaseDefinitionPage caseDefinitions(PageInput page) {
    Collection<CaseDefinition> allDefs = definitionRegistry.allDefinitions();
    List<CaseDefinitionType> all = allDefs.stream().map(CaseDefinitionType::from).toList();

    int offset = page != null && page.offset() != null ? page.offset() : 0;
    int limit = page != null && page.limit() != null ? page.limit() : 20;
    int end = Math.min(offset + limit, all.size());
    List<CaseDefinitionType> items = offset < all.size() ? all.subList(offset, end) : List.of();

    boolean hasNext = end < all.size();
    boolean hasPrevious = offset > 0;
    return new CaseDefinitionPage(items, new PageInfo(hasNext, hasPrevious, all.size(), null));
  }

  @Query
  @Description("Find a case definition by namespace, name, and optional version")
  public CaseDefinitionType caseDefinition(String namespace, String name, String version) {
    return definitionRegistry
        .findByIdentity(namespace, name, version)
        .map(definitionRegistry::getCaseDefinition)
        .map(CaseDefinitionType::from)
        .orElse(null);
  }

  @Query
  @Description("Retrieve event log entries for a case, optionally filtered by event type")
  public List<EventLogEntry> caseEvents(UUID caseId, List<String> eventTypes) {
    Set<CaseHubEventType> typeFilter = null;
    if (eventTypes != null && !eventTypes.isEmpty()) {
      typeFilter = eventTypes.stream().map(CaseHubEventType::valueOf).collect(Collectors.toSet());
    }

    var records =
        typeFilter != null ? runtime.eventLog(caseId, typeFilter) : runtime.eventLog(caseId);

    return records.stream().map(EventLogEntry::from).toList();
  }
}
