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
import io.casehub.engine.graphql.dto.CompensationStepType;
import io.casehub.engine.graphql.dto.CompensationTimelineType;
import io.casehub.engine.graphql.dto.EventLogEntry;
import io.casehub.engine.graphql.dto.TimelineStepType;
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

  @Query
  @Description(
      "Compensation timeline for a case — forward steps and compensation steps with saga status")
  public CompensationTimelineType compensationTimeline(UUID caseId) {
    String tenancyId = currentPrincipal.tenancyId();
    CaseInstance instance = instanceRepository.findByUuid(caseId, tenancyId);
    if (instance == null) {
      return null;
    }

    var compensationEvents =
        runtime.eventLog(
            caseId,
            java.util.Set.of(
                CaseHubEventType.COMPENSATION_STARTED,
                CaseHubEventType.COMPENSATION_COMPLETED,
                CaseHubEventType.COMPENSATION_FAULTED));
    if (compensationEvents.isEmpty()) {
      return null;
    }

    String triggeredBy = null;
    String reason = null;
    java.time.Instant compensationStartedAt = null;
    java.time.Instant compensationCompletedAt = null;
    for (var event : compensationEvents) {
      if (event.eventType() == CaseHubEventType.COMPENSATION_STARTED) {
        compensationStartedAt = event.timestamp();
        if (event.metadata() != null) {
          var meta = event.metadata();
          if (meta.has("triggeredBy")) {
            triggeredBy = meta.get("triggeredBy").asText();
          }
          if (meta.has("reason")) {
            reason = meta.get("reason").asText();
          }
        }
      } else if (event.eventType() == CaseHubEventType.COMPENSATION_COMPLETED
          || event.eventType() == CaseHubEventType.COMPENSATION_FAULTED) {
        compensationCompletedAt = event.timestamp();
      }
    }

    java.util.Set<String> compensationBindingNames = java.util.Set.of();
    java.util.Map<String, String> compensateRefMap = java.util.Map.of();
    io.casehub.engine.common.internal.model.CaseMetaModel caseMeta = instance.getCaseMetaModel();
    if (caseMeta != null) {
      var metaOpt =
          definitionRegistry.findByIdentity(
              caseMeta.getNamespace(), caseMeta.getName(), caseMeta.getVersion());
      if (metaOpt.isPresent()) {
        io.casehub.api.model.CaseDefinition def =
            definitionRegistry.getCaseDefinition(metaOpt.get());
        compensationBindingNames =
            def.getBindings().stream()
                .filter(io.casehub.api.model.Binding::isCompensation)
                .map(io.casehub.api.model.Binding::getName)
                .collect(java.util.stream.Collectors.toSet());
        java.util.Map<String, String> refMap = new java.util.HashMap<>();
        for (io.casehub.api.model.Binding b : def.getBindings()) {
          if (b.getCompensateRef() != null) {
            refMap.put(b.getCompensateRef(), b.getName());
          }
        }
        compensateRefMap = refMap;
      }
    }

    java.util.List<io.casehub.engine.common.internal.model.PlanItemRecord> planItems =
        planItemStore.findByCaseId(caseId, tenancyId);
    java.util.List<TimelineStepType> forwardSteps = new java.util.ArrayList<>();
    java.util.List<CompensationStepType> compensationSteps = new java.util.ArrayList<>();

    java.util.Set<String> finalCompBindingNames = compensationBindingNames;
    java.util.Map<String, String> finalRefMap = compensateRefMap;

    for (var pi : planItems) {
      String targetType =
          pi.targetType() != null
              ? pi.targetType().name().toLowerCase().replace('_', '-')
              : "unknown";
      if (finalCompBindingNames.contains(pi.bindingName())) {
        String compensatesBinding = finalRefMap.getOrDefault(pi.bindingName(), null);
        compensationSteps.add(
            new CompensationStepType(
                pi.planItemId(),
                pi.bindingName(),
                targetType,
                pi.status().name(),
                pi.createdAt(),
                pi.completedAt(),
                compensatesBinding,
                null));
      } else {
        forwardSteps.add(
            new TimelineStepType(
                pi.planItemId(),
                pi.bindingName(),
                targetType,
                pi.status().name(),
                pi.createdAt(),
                pi.completedAt()));
      }
    }

    return new CompensationTimelineType(
        caseId,
        instance.getState().name(),
        triggeredBy,
        reason,
        compensationStartedAt,
        compensationCompletedAt,
        forwardSteps,
        compensationSteps);
  }
}
