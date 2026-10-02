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

import com.fasterxml.jackson.databind.ObjectMapper;
import io.casehub.api.engine.CaseHubRuntime;
import io.casehub.api.model.Binding;
import io.casehub.api.model.CaseDefinition;
import io.casehub.api.model.event.CaseHubEventType;
import io.casehub.engine.common.internal.model.CaseInstance;
import io.casehub.engine.common.internal.model.CaseMetaModel;
import io.casehub.engine.common.internal.model.PlanItemRecord;
import io.casehub.engine.common.spi.CaseDefinitionRegistry;
import io.casehub.engine.common.spi.CaseInstanceRepository;
import io.casehub.engine.common.spi.PlanItemStore;
import io.casehub.engine.graphql.dto.CompensationChainType;
import io.casehub.engine.graphql.dto.CompensationLedgerEntryType;
import io.casehub.engine.graphql.dto.CompensationStepType;
import io.casehub.engine.graphql.dto.CompensationTimelineType;
import io.casehub.engine.graphql.dto.TimelineStepType;
import io.casehub.ledger.model.CaseLedgerEntry;
import io.casehub.ledger.repository.CaseLedgerEntryRepository;
import io.casehub.platform.api.identity.CurrentPrincipal;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@ApplicationScoped
public class CompensationQueryService {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  @Inject CaseInstanceRepository instanceRepository;
  @Inject CaseDefinitionRegistry definitionRegistry;
  @Inject CaseHubRuntime runtime;
  @Inject PlanItemStore planItemStore;
  @Inject CurrentPrincipal currentPrincipal;
  @Inject CaseLedgerEntryRepository ledgerRepository;

  public CompensationTimelineType compensationTimeline(UUID caseId) {
    String tenancyId = currentPrincipal.tenancyId();
    CaseInstance instance = instanceRepository.findByUuid(caseId, tenancyId).orElse(null);
    if (instance == null) {
      return null;
    }

    var compensationEvents =
        runtime.eventLog(
            caseId,
            Set.of(
                CaseHubEventType.COMPENSATION_STARTED,
                CaseHubEventType.COMPENSATION_COMPLETED,
                CaseHubEventType.COMPENSATION_FAULTED));
    if (compensationEvents.isEmpty()) {
      return null;
    }

    String triggeredBy = null;
    String reason = null;
    Instant compensationStartedAt = null;
    Instant compensationCompletedAt = null;
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

    Set<String> compensationBindingNames = Set.of();
    Map<String, String> compensatedByMap = Map.of();
    CaseMetaModel caseMeta = instance.getCaseMetaModel();
    if (caseMeta != null) {
      var metaOpt =
          definitionRegistry.findByIdentity(
              caseMeta.getNamespace(), caseMeta.getName(), caseMeta.getVersion());
      if (metaOpt.isPresent()) {
        CaseDefinition def = definitionRegistry.getCaseDefinition(metaOpt.get());
        compensationBindingNames =
            def.getBindings().stream()
                .filter(Binding::isCompensation)
                .map(Binding::getName)
                .collect(Collectors.toSet());
        Map<String, String> refMap = new HashMap<>();
        for (Binding b : def.getBindings()) {
          if (b.getCompensatedBy() != null) {
            refMap.put(b.getCompensatedBy(), b.getName());
          }
        }
        compensatedByMap = refMap;
      }
    }

    List<PlanItemRecord> planItems = planItemStore.findByCaseId(caseId, tenancyId);
    List<TimelineStepType> forwardSteps = new ArrayList<>();
    List<CompensationStepType> compensationSteps = new ArrayList<>();

    Set<String> finalCompBindingNames = compensationBindingNames;
    Map<String, String> finalRefMap = compensatedByMap;

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

  public CompensationChainType compensationChain(UUID caseId) {
    var entries = ledgerRepository.findByCaseId(caseId);
    var compensationEntries = new ArrayList<CompensationLedgerEntryType>();
    for (CaseLedgerEntry entry : entries) {
      if (entry.supplementJson == null || !entry.supplementJson.contains("\"COMPENSATION\"")) {
        continue;
      }
      try {
        var comp = MAPPER.readTree(entry.supplementJson).get("COMPENSATION");
        if (comp == null) continue;
        UUID origId =
            comp.has("originalEntryId")
                ? UUID.fromString(comp.get("originalEntryId").asText())
                : null;
        String compReason =
            comp.has("compensationReason") ? comp.get("compensationReason").asText() : null;
        String basis = comp.has("regulatoryBasis") ? comp.get("regulatoryBasis").asText() : null;
        String mode = comp.has("compensationMode") ? comp.get("compensationMode").asText() : null;
        compensationEntries.add(
            new CompensationLedgerEntryType(
                entry.id,
                entry.occurredAt,
                entry.eventType,
                entry.caseStatus,
                entry.causedByEntryId,
                origId,
                compReason,
                basis,
                mode));
      } catch (Exception ignored) {
      }
    }
    return new CompensationChainType(caseId, compensationEntries);
  }
}
