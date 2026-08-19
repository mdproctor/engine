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

import io.casehub.api.acl.EngineResourceTypes;
import io.casehub.api.acl.EngineWorkerActions;
import io.casehub.engine.internal.acl.WorkerGrantOrchestrator;
import io.casehub.engine.internal.acl.WorkerIdentity;
import io.casehub.engine.internal.acl.WorkerIdentityResolver;
import io.casehub.platform.acl.inmem.InMemoryWorkerCredentialStore;
import io.casehub.platform.api.acl.AccessControlProvider;
import io.casehub.platform.api.acl.AclAction;
import io.casehub.platform.api.acl.ResourceId;
import io.casehub.platform.api.acl.WorkerCredential;
import io.casehub.platform.api.acl.WorkerCredentialStore;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

@QuarkusTest
class WorkerRightsIntegrationTest {

  @Inject WorkerGrantOrchestrator orchestrator;
  @Inject WorkerCredentialStore credentialStore;
  @Inject AccessControlProvider accessControl;
  @Inject WorkerIdentityResolver identityResolver;

  @BeforeEach
  void cleanStore() {
    if (credentialStore instanceof InMemoryWorkerCredentialStore mem) {
      mem.revokeByResource(
          new ResourceId(EngineResourceTypes.CASE, "00000000-0000-0000-0000-000000000000"));
    }
  }

  @Test
  void grantAndMint_createsCredentialWithAclGrants() {
    UUID caseId = UUID.randomUUID();

    WorkerCredential credential =
        orchestrator.grantAndMint(
            "agent:test-pool",
            List.of(EngineWorkerActions.READ_CONTEXT, EngineWorkerActions.SIGNAL_CASE),
            caseId,
            "test-tenant",
            Instant.now().plusSeconds(300),
            "ns/test/v1");

    assertThat(credential).isNotNull();
    assertThat(credential.actorId()).isEqualTo("agent:test-pool");
    assertThat(credential.resourceId())
        .isEqualTo(new ResourceId(EngineResourceTypes.CASE, caseId.toString()));
    assertThat(credential.actions())
        .containsExactlyInAnyOrder(
            EngineWorkerActions.READ_CONTEXT, EngineWorkerActions.SIGNAL_CASE);

    assertThat(credentialStore.lookup(credential.token())).isPresent();

    assertThat(
            accessControl.canAccess(
                "agent:test-pool",
                new ResourceId(EngineResourceTypes.CASE, caseId.toString()),
                AclAction.READ))
        .as("Worker should have READ grant")
        .isTrue();
    assertThat(
            accessControl.canAccess(
                "agent:test-pool",
                new ResourceId(EngineResourceTypes.CASE, caseId.toString()),
                AclAction.WRITE))
        .as("Worker should have WRITE grant (from SIGNAL_CASE)")
        .isTrue();
  }

  @Test
  void revokeForWorker_removesCredentialAndGrants() {
    UUID caseId = UUID.randomUUID();

    WorkerCredential credential =
        orchestrator.grantAndMint(
            null,
            List.of(EngineWorkerActions.READ_CONTEXT),
            caseId,
            "test-tenant",
            null,
            "ns/test/v1");

    String actorId = credential.actorId();
    assertThat(credentialStore.lookup(credential.token())).isPresent();
    assertThat(
            accessControl.canAccess(
                actorId,
                new ResourceId(EngineResourceTypes.CASE, caseId.toString()),
                AclAction.READ))
        .isTrue();

    orchestrator.revokeForWorker(credential.token(), actorId, caseId, true);

    assertThat(credentialStore.lookup(credential.token())).isEmpty();
    assertThat(
            accessControl.canAccess(
                actorId,
                new ResourceId(EngineResourceTypes.CASE, caseId.toString()),
                AclAction.READ))
        .isFalse();
  }

  @Test
  void revokeForCase_sweepsAllCredentials() {
    UUID caseId = UUID.randomUUID();

    WorkerCredential c1 =
        orchestrator.grantAndMint(
            null,
            List.of(EngineWorkerActions.READ_CONTEXT),
            caseId,
            "test-tenant",
            null,
            "ns/test/v1");
    WorkerCredential c2 =
        orchestrator.grantAndMint(
            null,
            List.of(EngineWorkerActions.READ_CONTEXT),
            caseId,
            "test-tenant",
            null,
            "ns/test/v1");

    assertThat(credentialStore.lookup(c1.token())).isPresent();
    assertThat(credentialStore.lookup(c2.token())).isPresent();

    orchestrator.revokeForCase(caseId);

    assertThat(credentialStore.lookup(c1.token())).isEmpty();
    assertThat(credentialStore.lookup(c2.token())).isEmpty();
  }

  @Test
  void ephemeralIdentity_isUnique() {
    UUID caseId = UUID.randomUUID();
    WorkerIdentity id1 = identityResolver.resolve(null, caseId);
    WorkerIdentity id2 = identityResolver.resolve(null, caseId);

    assertThat(id1.actorId()).startsWith("agent:worker-");
    assertThat(id2.actorId()).startsWith("agent:worker-");
    assertThat(id1.actorId()).isNotEqualTo(id2.actorId());
    assertThat(id1.ephemeral()).isTrue();
  }

  @Test
  void serviceAccountIdentity_usesProvidedId() {
    UUID caseId = UUID.randomUUID();
    WorkerIdentity id = identityResolver.resolve("agent:pool-1@acme.io", caseId);

    assertThat(id.actorId()).isEqualTo("agent:pool-1@acme.io");
    assertThat(id.ephemeral()).isFalse();
  }

  @Test
  void sharedServiceAccount_differentialRevocation() {
    UUID caseId = UUID.randomUUID();
    String actorId = "agent:shared-pool";

    WorkerCredential c1 =
        orchestrator.grantAndMint(
            actorId,
            List.of(EngineWorkerActions.READ_CONTEXT, EngineWorkerActions.SIGNAL_CASE),
            caseId,
            "test-tenant",
            null,
            "ns/test/v1");
    WorkerCredential c2 =
        orchestrator.grantAndMint(
            actorId,
            List.of(EngineWorkerActions.READ_CONTEXT, EngineWorkerActions.ADMIN),
            caseId,
            "test-tenant",
            null,
            "ns/test/v1");

    orchestrator.revokeForWorker(c1.token(), actorId, caseId, false);

    assertThat(credentialStore.lookup(c1.token())).isEmpty();
    assertThat(credentialStore.lookup(c2.token())).isPresent();
    assertThat(
            accessControl.canAccess(
                actorId,
                new ResourceId(EngineResourceTypes.CASE, caseId.toString()),
                AclAction.READ))
        .as("READ still needed by c2")
        .isTrue();
  }
}
