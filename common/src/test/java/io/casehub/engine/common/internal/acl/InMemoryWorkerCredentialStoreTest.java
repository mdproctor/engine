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
package io.casehub.engine.common.internal.acl;

import static org.junit.jupiter.api.Assertions.*;

import io.casehub.api.model.acl.WorkerAction;
import io.casehub.api.model.acl.WorkerCredential;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class InMemoryWorkerCredentialStoreTest {

  private InMemoryWorkerCredentialStore store;

  @BeforeEach
  void setUp() {
    store = new InMemoryWorkerCredentialStore();
  }

  @Test
  void storeAndLookup() {
    var credential = credential("token-1", "agent:w1", UUID.randomUUID());
    store.store(credential);
    var found = store.lookup("token-1");
    assertTrue(found.isPresent());
    assertEquals("agent:w1", found.get().actorId());
  }

  @Test
  void lookupMissing_returnsEmpty() {
    assertTrue(store.lookup("nonexistent").isEmpty());
  }

  @Test
  void revoke_removesCredential() {
    var credential = credential("token-1", "agent:w1", UUID.randomUUID());
    store.store(credential);
    store.revoke("token-1");
    assertTrue(store.lookup("token-1").isEmpty());
  }

  @Test
  void revokeByCase_removesAllForCase() {
    UUID caseId = UUID.randomUUID();
    store.store(credential("t1", "agent:w1", caseId));
    store.store(credential("t2", "agent:w2", caseId));
    store.store(credential("t3", "agent:w3", UUID.randomUUID()));

    var revoked = store.revokeByCase(caseId);
    assertEquals(2, revoked.size());
    assertTrue(store.lookup("t1").isEmpty());
    assertTrue(store.lookup("t2").isEmpty());
    assertTrue(store.lookup("t3").isPresent());
  }

  @Test
  void revokeByActor_removesAllForActor() {
    store.store(credential("t1", "agent:pool", UUID.randomUUID()));
    store.store(credential("t2", "agent:pool", UUID.randomUUID()));
    store.store(credential("t3", "agent:other", UUID.randomUUID()));

    var revoked = store.revokeByActor("agent:pool");
    assertEquals(2, revoked.size());
    assertTrue(store.lookup("t3").isPresent());
  }

  @Test
  void findActiveByActorAndCase_returnsMatching() {
    UUID caseId = UUID.randomUUID();
    store.store(credential("t1", "agent:pool", caseId));
    store.store(credential("t2", "agent:pool", caseId));
    store.store(credential("t3", "agent:pool", UUID.randomUUID()));

    var active = store.findActiveByActorAndCase("agent:pool", caseId);
    assertEquals(2, active.size());
  }

  @Test
  void findActiveByActorAndCase_noMatches_returnsEmpty() {
    var active = store.findActiveByActorAndCase("agent:unknown", UUID.randomUUID());
    assertTrue(active.isEmpty());
  }

  @Test
  void revokeByCase_noMatches_returnsEmpty() {
    var revoked = store.revokeByCase(UUID.randomUUID());
    assertTrue(revoked.isEmpty());
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
