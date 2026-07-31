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
package io.casehub.engine;

import static org.assertj.core.api.Assertions.assertThat;

import io.casehub.api.engine.CaseHub;
import io.casehub.api.engine.CaseHubRuntime;
import io.casehub.api.model.CaseDefinition;
import io.casehub.platform.api.acl.AccessControlProvider;
import io.casehub.platform.api.acl.AclAction;
import io.casehub.platform.api.identity.CurrentPrincipal;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

@QuarkusTest
class AuthorizationGrantIntegrationTest {

  @Inject AuthorizationCaseHubBean caseHubBean;
  @Inject NoAuthCaseHubBean noAuthCaseHubBean;
  @Inject CaseHubRuntime runtime;
  @Inject AccessControlProvider accessControl;
  @Inject CurrentPrincipal currentPrincipal;

  @Test
  void authorizationGrantsCreatedOnCaseStart() {
    UUID caseId = caseHubBean.startCase(Map.of("status", "pending"));

    assertThat(accessControl.canAccess("group:case-manager", "case:" + caseId, AclAction.READ))
        .as("case-manager group should have READ grant")
        .isTrue();
    assertThat(accessControl.canAccess("group:auditor", "case:" + caseId, AclAction.READ))
        .as("auditor group should have READ grant")
        .isTrue();
    assertThat(accessControl.canAccess("group:case-manager", "case:" + caseId, AclAction.WRITE))
        .as("case-manager group should have WRITE grant")
        .isTrue();
    assertThat(accessControl.canAccess("group:supervisor", "case:" + caseId, AclAction.ADMIN))
        .as("supervisor group should have ADMIN grant")
        .isTrue();
    assertThat(accessControl.canAccess("group:case-worker", "case:" + caseId, AclAction.CLAIM))
        .as("case-worker group should have CLAIM grant")
        .isTrue();

    assertThat(
            accessControl.canAccess(currentPrincipal.actorId(), "case:" + caseId, AclAction.ADMIN))
        .as("case creator should have automatic ADMIN grant")
        .isTrue();
  }

  @Test
  void noGrantsCreatedWhenAuthorizationAbsent() {
    UUID caseId = noAuthCaseHubBean.startCase(Map.of("status", "pending"));

    assertThat(accessControl.canAccess("group:anyone", "case:" + caseId, AclAction.READ))
        .as("no grants should exist when authorization is absent")
        .isFalse();
  }

  @ApplicationScoped
  static class AuthorizationCaseHubBean extends CaseHub {
    @Override
    public CaseDefinition getDefinition() {
      return CaseDefinition.builder()
          .namespace("acl-test")
          .name("auth-case")
          .version("1.0")
          .authorization(AclAction.READ, List.of("case-manager", "auditor"))
          .authorization(AclAction.WRITE, List.of("case-manager"))
          .authorization(AclAction.ADMIN, List.of("supervisor"))
          .authorization(AclAction.CLAIM, List.of("case-worker"))
          .build();
    }
  }

  @ApplicationScoped
  static class NoAuthCaseHubBean extends CaseHub {
    @Override
    public CaseDefinition getDefinition() {
      return CaseDefinition.builder()
          .namespace("acl-test")
          .name("no-auth-case")
          .version("1.0")
          .build();
    }
  }
}
