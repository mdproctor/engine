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

import io.casehub.api.model.acl.WorkerCredential;
import io.casehub.engine.common.spi.acl.WorkerCredentialStore;
import io.quarkus.arc.DefaultBean;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@DefaultBean
@ApplicationScoped
public class InMemoryWorkerCredentialStore implements WorkerCredentialStore {

  private final ConcurrentHashMap<String, WorkerCredential> store = new ConcurrentHashMap<>();

  @Override
  public void store(WorkerCredential credential) {
    store.put(credential.token(), credential);
  }

  @Override
  public Optional<WorkerCredential> lookup(String token) {
    return Optional.ofNullable(store.get(token));
  }

  @Override
  public void revoke(String token) {
    store.remove(token);
  }

  @Override
  public List<WorkerCredential> revokeByCase(UUID caseId) {
    var revoked = store.values().stream().filter(c -> c.caseId().equals(caseId)).toList();
    revoked.forEach(c -> store.remove(c.token()));
    return revoked;
  }

  @Override
  public List<WorkerCredential> revokeByActor(String actorId) {
    var revoked = store.values().stream().filter(c -> c.actorId().equals(actorId)).toList();
    revoked.forEach(c -> store.remove(c.token()));
    return revoked;
  }

  @Override
  public List<WorkerCredential> findActiveByActorAndCase(String actorId, UUID caseId) {
    return store.values().stream()
        .filter(c -> c.actorId().equals(actorId) && c.caseId().equals(caseId) && !c.isExpired())
        .toList();
  }
}
