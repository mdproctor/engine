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

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import io.casehub.api.model.acl.WorkerAction;
import io.casehub.api.model.acl.WorkerCredential;
import io.casehub.engine.common.internal.acl.InMemoryWorkerCredentialStore;
import io.casehub.platform.api.acl.AccessControlProvider;
import io.casehub.platform.api.acl.AclAction;
import io.casehub.platform.api.acl.AclEntryRequest;
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
  private WorkerGrantOrchestrator orchestrator;

  @BeforeEach
  void setUp() {
    aclProvider = mock(AccessControlProvider.class);
    credentialStore = new InMemoryWorkerCredentialStore();
    identityResolver = new WorkerIdentityResolver();
    orchestrator = new WorkerGrantOrchestrator(aclProvider, credentialStore, identityResolver);
  }

  @Test
  void grantAndMint_createsCredentialAndGrants() {
    UUID caseId = UUID.randomUUID();
    var actions = List.of(WorkerAction.READ_CONTEXT, WorkerAction.SIGNAL_CASE);
    Instant deadline = Instant.now().plusSeconds(300);

    var credential = orchestrator.grantAndMint(null, actions, caseId, "tenant-1", deadline);

    assertNotNull(credential);
    assertTrue(credential.actorId().startsWith("agent:worker-"));
    assertEquals(caseId, credential.caseId());
    assertEquals(Set.copyOf(actions), credential.actions());
    assertTrue(credentialStore.lookup(credential.token()).isPresent());
    verify(aclProvider).grantBatch(anyCollection());
  }

  @Test
  void grantAndMint_withServiceAccount_usesIt() {
    UUID caseId = UUID.randomUUID();
    var actions = List.of(WorkerAction.READ_CONTEXT);
    Instant deadline = Instant.now().plusSeconds(300);

    var credential =
        orchestrator.grantAndMint("agent:pool-1", actions, caseId, "tenant-1", deadline);

    assertEquals("agent:pool-1", credential.actorId());
  }

  @Test
  void grantAndMint_deduplicatesGrants() {
    UUID caseId = UUID.randomUUID();
    var actions =
        List.of(WorkerAction.WRITE_CONTEXT, WorkerAction.SIGNAL_CASE, WorkerAction.SPAWN_SUB_CASE);
    Instant deadline = Instant.now().plusSeconds(300);

    orchestrator.grantAndMint(null, actions, caseId, "tenant-1", deadline);

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
    var actions = List.of(WorkerAction.READ_CONTEXT);
    Instant farFuture = Instant.now().plusSeconds(86400);

    var credential = orchestrator.grantAndMint(null, actions, caseId, "tenant-1", farFuture);

    long secondsUntilExpiry =
        credential.expiresAt().getEpochSecond() - Instant.now().getEpochSecond();
    assertTrue(secondsUntilExpiry <= 3601);
  }

  @Test
  void revokeForWorker_ephemeral_revokesAll() {
    UUID caseId = UUID.randomUUID();
    var credential =
        orchestrator.grantAndMint(null, List.of(WorkerAction.READ_CONTEXT), caseId, "t1", null);

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
            caseId,
            Set.of(WorkerAction.READ_CONTEXT, WorkerAction.SIGNAL_CASE),
            Instant.now().plusSeconds(3600),
            Instant.now()));
    credentialStore.store(
        new WorkerCredential(
            "t2",
            "agent:pool",
            caseId,
            Set.of(WorkerAction.READ_CONTEXT, WorkerAction.ADMIN),
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
    verify(aclProvider, times(2)).revokeAll(anyString(), anyString());
  }

  @Test
  void revokeForCase_emptyStore_noOp() {
    orchestrator.revokeForCase(UUID.randomUUID());
    verify(aclProvider, never()).revokeAll(anyString(), anyString());
  }

  private WorkerCredential credential(String token, String actorId, UUID caseId) {
    return new WorkerCredential(
        token,
        actorId,
        caseId,
        Set.of(WorkerAction.READ_CONTEXT),
        Instant.now().plusSeconds(3600),
        Instant.now());
  }
}
