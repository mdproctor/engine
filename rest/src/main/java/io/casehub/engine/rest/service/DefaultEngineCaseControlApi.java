package io.casehub.engine.rest.service;

import io.casehub.api.spi.EngineCaseControlApi;
import io.casehub.api.view.CaseControlRequest;
import io.casehub.api.view.CaseControlView;
import io.casehub.api.view.SendSignalRequest;
import io.casehub.api.view.SignalResultView;
import io.casehub.api.engine.CaseHubRuntime;
import io.casehub.engine.common.internal.model.CaseInstance;
import io.casehub.engine.common.spi.CaseInstanceRepository;
import io.casehub.platform.api.acl.AclAction;
import io.casehub.platform.api.identity.CurrentPrincipal;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.UUID;

@ApplicationScoped
public class DefaultEngineCaseControlApi implements EngineCaseControlApi {

  @Inject CaseService caseService;
  @Inject CaseHubRuntime runtime;
  @Inject CaseInstanceRepository instanceRepository;
  @Inject CurrentPrincipal currentPrincipal;

  @Override
  public CaseControlView suspendCase(UUID caseId, CaseControlRequest request, String tenancyId) {
    caseService.requireCaseAccess(caseId, AclAction.ADMIN);
    runtime.suspendCase(caseId);
    String resolvedTenancyId = tenancyId != null ? tenancyId : currentPrincipal.tenancyId();
    CaseInstance instance = caseService.requireCase(caseId, resolvedTenancyId);
    return new CaseControlView(caseId, instance.getState());
  }

  @Override
  public CaseControlView resumeCase(UUID caseId, CaseControlRequest request, String tenancyId) {
    caseService.requireCaseAccess(caseId, AclAction.ADMIN);
    runtime.resumeCase(caseId);
    String resolvedTenancyId = tenancyId != null ? tenancyId : currentPrincipal.tenancyId();
    CaseInstance instance = caseService.requireCase(caseId, resolvedTenancyId);
    return new CaseControlView(caseId, instance.getState());
  }

  @Override
  public CaseControlView cancelCase(UUID caseId, CaseControlRequest request, String tenancyId) {
    caseService.requireCaseAccess(caseId, AclAction.ADMIN);
    runtime.cancelCase(caseId);
    String resolvedTenancyId = tenancyId != null ? tenancyId : currentPrincipal.tenancyId();
    CaseInstance instance = caseService.requireCase(caseId, resolvedTenancyId);
    return new CaseControlView(caseId, instance.getState());
  }

  @Override
  public SignalResultView sendSignal(UUID caseId, SendSignalRequest request, String tenancyId) {
    caseService.requireCaseAccess(caseId, AclAction.WRITE);
    runtime.signal(caseId, request.path(), request.value());
    return new SignalResultView(caseId, true);
  }
}
