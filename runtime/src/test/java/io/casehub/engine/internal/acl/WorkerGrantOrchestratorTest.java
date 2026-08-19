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
package io.casehub.engine.internal.acl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyCollection;
import static org.mockito.Mockito.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import io.casehub.api.acl.EngineResourceTypes;
import io.casehub.api.acl.EngineWorkerActions;
import io.casehub.platform.acl.inmem.InMemoryWorkerCredentialStore;
import io.casehub.platform.api.acl.AccessControlProvider;
import io.casehub.platform.api.acl.AclAction;
import io.casehub.platform.api.acl.AclEntryRequest;
import io.casehub.platform.api.acl.AuthorizationDecision;
import io.casehub.platform.api.acl.ResourceId;
import io.casehub.platform.api.acl.WorkerAuthorizationDeniedException;
import io.casehub.platform.api.acl.WorkerAuthorizationPolicy;
import io.casehub.platform.api.acl.WorkerCredential;
import io.casehub.platform.api.acl.WorkerCredentialStore;
import io.casehub.platform.api.acl.WorkerPermissionRequest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class WorkerGrantOrchestratorTest {

  private AccessControlProvider aclProvider;
  private InMemoryWorkerCredentialStore credentialStore;
  private WorkerIdentityResolver identityResolver;
  private WorkerAuthorizationPolicy autoApprovePolicy;
  private WorkerGrantOrchestrator orchestrator;

  @BeforeEach
  void setUp() {
    aclProvider = mock(AccessControlProvider.class);
    credentialStore = new InMemoryWorkerCredentialStore();
    identityResolver = new WorkerIdentityResolver();
    autoApprovePolicy = new WorkerAuthorizationPolicy() {};
    orchestrator =
        new WorkerGrantOrchestrator(
            aclProvider, credentialStore, identityResolver, autoApprovePolicy);
  }

  @Test
  void grantAndMint_createsCredentialAndGrants() {
    UUID caseId = UUID.randomUUID();
    var actions = List.of(EngineWorkerActions.READ_CONTEXT, EngineWorkerActions.SIGNAL_CASE);
    Instant deadline = Instant.now().plusSeconds(300);

    var credential =
        orchestrator.grantAndMint(null, actions, caseId, "tenant-1", deadline, "ns/test/v1");

    assertNotNull(credential);
    assertTrue(credential.actorId().startsWith("agent:worker-"));
    assertEquals(
        new ResourceId(EngineResourceTypes.CASE, caseId.toString()), credential.resourceId());
    assertEquals("tenant-1", credential.tenancyId());
    assertEquals(Set.copyOf(actions), credential.actions());
    assertTrue(credentialStore.lookup(credential.token()).isPresent());
    verify(aclProvider).grantBatch(anyCollection());
  }

  @Test
  void grantAndMint_withServiceAccount_usesIt() {
    UUID caseId = UUID.randomUUID();
    var actions = List.of(EngineWorkerActions.READ_CONTEXT);
    Instant deadline = Instant.now().plusSeconds(300);

    var credential =
        orchestrator.grantAndMint(
            "agent:pool-1", actions, caseId, "tenant-1", deadline, "ns/test/v1");

    assertEquals("agent:pool-1", credential.actorId());
  }

  @Test
  void grantAndMint_deduplicatesGrants() {
    UUID caseId = UUID.randomUUID();
    var actions =
        List.of(
            EngineWorkerActions.WRITE_CONTEXT,
            EngineWorkerActions.SIGNAL_CASE,
            EngineWorkerActions.SPAWN_SUB_CASE);
    Instant deadline = Instant.now().plusSeconds(300);

    orchestrator.grantAndMint(null, actions, caseId, "tenant-1", deadline, "ns/test/v1");

    @SuppressWarnings("unchecked")
    ArgumentCaptor<Collection<AclEntryRequest>> captor = ArgumentCaptor.forClass(Collection.class);
    verify(aclProvider).grantBatch(captor.capture());
    var requests = new ArrayList<>(captor.getValue());
    assertEquals(1, requests.size());
    assertEquals(AclAction.WRITE, requests.get(0).action());
  }

  @Test
  void grantAndMint_expiryClampedToOneHour() {
    UUID caseId = UUID.randomUUID();
    var actions = List.of(EngineWorkerActions.READ_CONTEXT);
    Instant farFuture = Instant.now().plusSeconds(86400);

    var credential =
        orchestrator.grantAndMint(null, actions, caseId, "tenant-1", farFuture, "ns/test/v1");

    long secondsUntilExpiry =
        credential.expiresAt().getEpochSecond() - Instant.now().getEpochSecond();
    assertTrue(secondsUntilExpiry <= 3601);
  }

  @Test
  void revokeForWorker_ephemeral_revokesAll() {
    UUID caseId = UUID.randomUUID();
    var credential =
        orchestrator.grantAndMint(
            null, List.of(EngineWorkerActions.READ_CONTEXT), caseId, "t1", null, "ns/test/v1");

    orchestrator.revokeForWorker(credential.token(), credential.actorId(), caseId, true);

    assertTrue(credentialStore.lookup(credential.token()).isEmpty());
    verify(aclProvider, times(1)).revokeBatch(anyCollection());
  }

  @Test
  void revokeForWorker_sharedServiceAccount_onlyRevokesUnneededGrants() {
    UUID caseId = UUID.randomUUID();
    credentialStore.store(
        new WorkerCredential(
            "t1",
            "agent:pool",
            new ResourceId(EngineResourceTypes.CASE, caseId.toString()),
            "tenant-1",
            Set.of(EngineWorkerActions.READ_CONTEXT, EngineWorkerActions.SIGNAL_CASE),
            Instant.now().plusSeconds(3600),
            Instant.now()));
    credentialStore.store(
        new WorkerCredential(
            "t2",
            "agent:pool",
            new ResourceId(EngineResourceTypes.CASE, caseId.toString()),
            "tenant-1",
            Set.of(EngineWorkerActions.READ_CONTEXT, EngineWorkerActions.ADMIN),
            Instant.now().plusSeconds(3600),
            Instant.now()));

    orchestrator.revokeForWorker("t1", "agent:pool", caseId, false);

    assertTrue(credentialStore.lookup("t1").isEmpty());
    assertTrue(credentialStore.lookup("t2").isPresent());
    @SuppressWarnings("unchecked")
    ArgumentCaptor<Collection<AclEntryRequest>> captor = ArgumentCaptor.forClass(Collection.class);
    verify(aclProvider).revokeBatch(captor.capture());
    var revoked = new ArrayList<>(captor.getValue());
    assertEquals(1, revoked.size());
    assertEquals(AclAction.WRITE, revoked.get(0).action());
  }

  @Test
  void revokeForCase_sweepsAll() {
    UUID caseId = UUID.randomUUID();
    credentialStore.store(credential("t1", "agent:w1", caseId));
    credentialStore.store(credential("t2", "agent:w2", caseId));

    orchestrator.revokeForCase(caseId);

    assertTrue(credentialStore.lookup("t1").isEmpty());
    assertTrue(credentialStore.lookup("t2").isEmpty());
    verify(aclProvider, times(2)).revokeAll(anyString(), any(ResourceId.class));
  }

  @Test
  void revokeForCase_emptyStore_noOp() {
    orchestrator.revokeForCase(UUID.randomUUID());
    verify(aclProvider, never()).revokeAll(anyString(), any(ResourceId.class));
  }

  @Test
  void grantAndMint_policyDenial_throwsAndCreatesNoGrants() {
    WorkerAuthorizationPolicy denyPolicy =
        new WorkerAuthorizationPolicy() {
          @Override
          public AuthorizationDecision evaluate(WorkerPermissionRequest request) {
            return AuthorizationDecision.deny("ADMIN not allowed for ephemeral workers");
          }
        };
    var restrictedOrchestrator =
        new WorkerGrantOrchestrator(aclProvider, credentialStore, identityResolver, denyPolicy);

    UUID caseId = UUID.randomUUID();
    var actions = List.of(EngineWorkerActions.ADMIN);

    assertThrows(
        WorkerAuthorizationDeniedException.class,
        () ->
            restrictedOrchestrator.grantAndMint(
                null, actions, caseId, "tenant-1", null, "ns/def/v1"));

    verify(aclProvider, never()).grantBatch(anyCollection());
  }

  @Test
  void grantAndMint_storeFailure_compensatesWithRevoke() {
    WorkerCredentialStore failingStore = mock(WorkerCredentialStore.class);
    doThrow(new RuntimeException("store failed")).when(failingStore).store(any());
    var compensatingOrchestrator =
        new WorkerGrantOrchestrator(aclProvider, failingStore, identityResolver, autoApprovePolicy);

    UUID caseId = UUID.randomUUID();
    var actions = List.of(EngineWorkerActions.READ_CONTEXT);

    assertThrows(
        RuntimeException.class,
        () ->
            compensatingOrchestrator.grantAndMint(
                null, actions, caseId, "tenant-1", null, "ns/def/v1"));

    verify(aclProvider).grantBatch(anyCollection());
    verify(aclProvider).revokeBatch(anyCollection());
  }

  private WorkerCredential credential(String token, String actorId, UUID caseId) {
    return new WorkerCredential(
        token,
        actorId,
        new ResourceId(EngineResourceTypes.CASE, caseId.toString()),
        "tenant-1",
        Set.of(EngineWorkerActions.READ_CONTEXT),
        Instant.now().plusSeconds(3600),
        Instant.now());
  }
}
