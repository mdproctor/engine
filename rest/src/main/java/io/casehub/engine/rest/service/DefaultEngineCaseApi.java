package io.casehub.engine.rest.service;

import io.casehub.api.model.CaseStatus;
import io.casehub.api.spi.EngineCaseApi;
import io.casehub.api.view.CaseContextChangeEventView;
import io.casehub.api.view.CaseControlView;
import io.casehub.api.view.CaseInstanceView;
import io.casehub.api.view.CaseLifecycleEventView;
import io.casehub.api.view.CasePage;
import io.casehub.api.view.CaseStreamEventView;
import io.casehub.api.view.CompletionSummaryView;
import io.casehub.api.view.GoalEvaluationView;
import io.casehub.api.view.GoalStatusView;
import io.casehub.api.view.PlanItemView;
import io.casehub.api.view.StartCaseRequest;
import io.casehub.engine.common.internal.model.CaseInstance;
import io.casehub.engine.common.internal.model.CaseMetaModel;
import io.casehub.engine.common.internal.model.PlanItemRecord;
import io.casehub.engine.common.spi.CaseInstanceRepository;
import io.casehub.engine.common.spi.PlanItemStore;
import io.casehub.engine.common.spi.query.CaseInstanceQuery;
import io.casehub.engine.rest.CaseStreamBroadcaster;
import io.casehub.engine.rest.dto.CompletionStatus;
import io.casehub.engine.rest.dto.GoalEvaluationResponse;
import io.casehub.api.engine.CaseHubRuntime;
import io.casehub.platform.api.acl.AccessControlProvider;
import io.casehub.platform.api.acl.AclAction;
import io.casehub.platform.api.acl.ResourceId;
import io.casehub.platform.api.identity.CurrentPrincipal;
import io.smallrye.mutiny.Multi;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@ApplicationScoped
public class DefaultEngineCaseApi implements EngineCaseApi {

  @Inject CaseService caseService;
  @Inject CaseHubRuntime runtime;
  @Inject CaseInstanceRepository instanceRepository;
  @Inject PlanItemStore planItemStore;
  @Inject CurrentPrincipal currentPrincipal;
  @Inject AccessControlProvider accessControlProvider;
  @Inject CaseStreamBroadcaster caseStreamBroadcaster;

  @Override
  public CasePage listCases(
      CaseStatus status, String namespace, String name,
      String tenancyId, Integer offset, Integer limit) {
    int page = offset != null ? offset : 0;
    int size = limit != null ? limit : 20;
    var query = CaseInstanceQuery.builder()
        .status(status)
        .namespace(namespace)
        .name(name)
        .page(page)
        .size(size)
        .build();
    String resolvedTenancyId = tenancyId != null ? tenancyId : currentPrincipal.tenancyId();
    String actorId = currentPrincipal.actorId();
    var items = instanceRepository.query(query, resolvedTenancyId).stream()
        .filter(ci -> accessControlProvider.canAccess(
            actorId,
            new ResourceId(io.casehub.api.acl.EngineResourceTypes.CASE, ci.getUuid().toString()),
            AclAction.READ))
        .map(this::mapInstance)
        .toList();
    long total = items.size();
    return new CasePage(items, total, total > (long) page * size + size);
  }

  @Override
  public CaseInstanceView getCaseById(UUID caseId, String tenancyId) {
    var instance = caseService.requireCaseAccess(caseId, AclAction.READ);
    return mapInstance(instance);
  }

  @Override
  public CaseInstanceView startCase(StartCaseRequest request, String tenancyId) {
    String resolvedTenancyId = tenancyId != null ? tenancyId : currentPrincipal.tenancyId();
    var instance = caseService.startCase(
        request.namespace(), request.name(), request.version(),
        request.context(), resolvedTenancyId);
    return mapInstance(instance);
  }

  @Override
  @SuppressWarnings("unchecked")
  public Map<String, Object> getCaseContext(UUID caseId, String tenancyId) {
    caseService.requireCaseAccess(caseId, AclAction.READ);
    Object context = runtime.query(caseId, ".");
    return context instanceof Map ? (Map<String, Object>) context : Map.of();
  }

  @Override
  @SuppressWarnings("unchecked")
  public Map<String, Object> getCaseContextPath(UUID caseId, String path, String tenancyId) {
    caseService.requireCaseAccess(caseId, AclAction.READ);
    Object value = runtime.query(caseId, path);
    return value instanceof Map ? (Map<String, Object>) value : Map.of();
  }

  @Override
  public List<PlanItemView> getPlanItems(UUID caseId, String tenancyId) {
    caseService.requireCaseAccess(caseId, AclAction.READ);
    String resolvedTenancyId = tenancyId != null ? tenancyId : currentPrincipal.tenancyId();
    return planItemStore.findByCaseId(caseId, resolvedTenancyId).stream()
        .map(this::mapPlanItem)
        .toList();
  }

  @Override
  public GoalEvaluationView getGoals(UUID caseId, String tenancyId) {
    String resolvedTenancyId = tenancyId != null ? tenancyId : currentPrincipal.tenancyId();
    GoalEvaluationResponse response = caseService.evaluateGoals(caseId, resolvedTenancyId);
    return mapGoalEvaluation(response);
  }

  @Override
  public Multi<CaseStreamEventView> caseStream(UUID caseId) {
    return caseStreamBroadcaster.stream(caseId)
        .map(e -> new CaseStreamEventView(e.caseId(), e.type(), e.data()));
  }

  @Override
  public Multi<CaseLifecycleEventView> caseLifecycle(UUID caseId) {
    return Multi.createFrom().empty();
  }

  @Override
  public Multi<CaseContextChangeEventView> caseContextChange(UUID caseId) {
    return Multi.createFrom().empty();
  }

  private CaseInstanceView mapInstance(CaseInstance instance) {
    CaseMetaModel meta = instance.getCaseMetaModel();
    return new CaseInstanceView(
        instance.getUuid(),
        instance.getState(),
        meta.getNamespace(),
        meta.getName(),
        meta.getVersion(),
        instance.getCreatedAt(),
        instance.getActorId());
  }

  private PlanItemView mapPlanItem(PlanItemRecord record) {
    return new PlanItemView(
        UUID.fromString(record.planItemId()),
        record.bindingName(),
        record.status(),
        record.targetType().name().toLowerCase().replace('_', '-'),
        record.parentCompoundId() != null ? UUID.fromString(record.parentCompoundId()) : null);
  }

  private GoalEvaluationView mapGoalEvaluation(GoalEvaluationResponse response) {
    var goals = response.goals().stream()
        .map(g -> new GoalStatusView(g.name(), g.kind(), g.satisfied(), g.condition()))
        .toList();
    CompletionSummaryView completion = null;
    if (response.completion() != null) {
      var c = response.completion();
      if ("goal-based".equals(c.type())) {
        int total = c.byKind().size();
        int satisfied = (int) c.byKind().values().stream()
            .filter(CompletionStatus::satisfied).count();
        completion = new CompletionSummaryView(satisfied == total, satisfied, total, c.type());
      } else if ("predicate-based".equals(c.type())) {
        boolean sat = c.satisfied() != null && c.satisfied();
        completion = new CompletionSummaryView(sat, sat ? 1 : 0, 1, c.type());
      }
    }
    return new GoalEvaluationView(goals, completion);
  }
}
