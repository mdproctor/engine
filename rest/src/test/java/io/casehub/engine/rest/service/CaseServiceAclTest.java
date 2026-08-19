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
package io.casehub.engine.rest.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.casehub.api.acl.EngineResourceTypes;
import io.casehub.api.model.CaseStatus;
import io.casehub.engine.common.internal.model.CaseInstance;
import io.casehub.engine.common.spi.CaseInstanceRepository;
import io.casehub.engine.rest.exception.EntityNotFoundException;
import io.casehub.platform.api.acl.AccessControlProvider;
import io.casehub.platform.api.acl.AccessDeniedException;
import io.casehub.platform.api.acl.AclAction;
import io.casehub.platform.api.acl.ResourceId;
import io.casehub.platform.api.identity.CurrentPrincipal;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class CaseServiceAclTest {

  private CaseService caseService;
  private UUID caseId;
  private CaseInstance instance;
  private boolean aclAllowed;

  @BeforeEach
  void setUp() {
    caseId = UUID.randomUUID();
    instance = new CaseInstance();
    instance.setUuid(caseId);
    instance.setState(CaseStatus.RUNNING);
    aclAllowed = true;

    caseService = new CaseService();
    caseService.instanceRepository =
        new CaseInstanceRepository() {
          @Override
          public CaseInstance save(CaseInstance i, String t) {
            return i;
          }

          @Override
          public CaseInstance update(CaseInstance i, String t) {
            return i;
          }

          @Override
          public CaseInstance findByUuid(UUID uuid, String tenancyId) {
            return uuid.equals(caseId) && "t1".equals(tenancyId) ? instance : null;
          }

          @Override
          public void updateStateAndAppendEvent(
              CaseInstance i, io.casehub.engine.common.internal.history.EventLog e, String t) {}
        };
    caseService.accessControlProvider =
        new AccessControlProvider() {
          @Override
          public boolean canAccess(String actorId, ResourceId resourceId, AclAction action) {
            return aclAllowed;
          }
        };
    caseService.currentPrincipal =
        new CurrentPrincipal() {
          @Override
          public String actorId() {
            return "alice";
          }

          @Override
          public Set<String> groups() {
            return Set.of();
          }

          @Override
          public String tenancyId() {
            return "t1";
          }

          @Override
          public boolean isCrossTenantAdmin() {
            return false;
          }
        };
  }

  @Test
  void requireCaseAccess_allowed_returnsInstance() {
    CaseInstance result = caseService.requireCaseAccess(caseId, AclAction.READ);
    assertThat(result).isSameAs(instance);
  }

  @Test
  void requireCaseAccess_denied_throwsAccessDenied() {
    aclAllowed = false;
    assertThatThrownBy(() -> caseService.requireCaseAccess(caseId, AclAction.WRITE))
        .isInstanceOf(AccessDeniedException.class);
  }

  @Test
  void requireCaseAccess_notFound_throwsEntityNotFound() {
    UUID unknownId = UUID.randomUUID();
    assertThatThrownBy(() -> caseService.requireCaseAccess(unknownId, AclAction.READ))
        .isInstanceOf(EntityNotFoundException.class);
  }

  @Test
  void requireCaseAccess_checksCorrectResourceId() {
    final ResourceId[] capturedResourceId = {null};
    caseService.accessControlProvider =
        new AccessControlProvider() {
          @Override
          public boolean canAccess(String actorId, ResourceId resourceId, AclAction action) {
            capturedResourceId[0] = resourceId;
            return true;
          }
        };

    caseService.requireCaseAccess(caseId, AclAction.ADMIN);
    assertThat(capturedResourceId[0])
        .isEqualTo(new ResourceId(EngineResourceTypes.CASE, caseId.toString()));
  }
}
