package io.casehub.engine.rest.service;

import io.casehub.api.spi.EngineEventLogApi;
import io.casehub.api.view.EventLogEntryView;
import io.casehub.api.view.EventLogPage;
import io.casehub.api.model.event.CaseHubEventType;
import io.casehub.api.model.event.EventStreamType;
import io.casehub.engine.common.spi.EventLogRepository;
import io.casehub.engine.common.spi.query.EventLogQuery;
import io.casehub.platform.api.acl.AclAction;
import io.casehub.platform.api.identity.CurrentPrincipal;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@ApplicationScoped
public class DefaultEngineEventLogApi implements EngineEventLogApi {

  @Inject CaseService caseService;
  @Inject EventLogRepository eventLogRepository;
  @Inject CurrentPrincipal currentPrincipal;
  @Inject ObjectMapper objectMapper;

  @Override
  public EventLogPage getEventLog(
      UUID caseId, String tenancyId, Integer offset, Integer limit,
      List<String> eventTypes, List<String> streamTypes) {
    caseService.requireCaseAccess(caseId, AclAction.READ);
    String resolvedTenancyId = tenancyId != null ? tenancyId : currentPrincipal.tenancyId();
    int page = offset != null ? offset : 0;
    int size = limit != null ? limit : 50;

    var queryBuilder = EventLogQuery.builder(caseId)
        .page(page)
        .size(size);

    if (eventTypes != null && !eventTypes.isEmpty()) {
      queryBuilder.eventTypes(
          eventTypes.stream().map(CaseHubEventType::valueOf).toList());
    }
    if (streamTypes != null && !streamTypes.isEmpty()) {
      queryBuilder.streamTypes(
          streamTypes.stream().map(EventStreamType::valueOf).toList());
    }

    var logs = eventLogRepository.query(queryBuilder.build(), resolvedTenancyId);
    var items = logs.stream()
        .map(log -> new EventLogEntryView(
            log.getEventType().name(),
            log.getStreamType().name(),
            log.getTimestamp(),
            jsonNodeToMap(log.getPayload())))
        .toList();
    return new EventLogPage(items, items.size(), false);
  }

  @SuppressWarnings("unchecked")
  private Map<String, Object> jsonNodeToMap(JsonNode node) {
    if (node == null || node.isNull()) {
      return Collections.emptyMap();
    }
    return objectMapper.convertValue(node, Map.class);
  }
}
