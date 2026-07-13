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
package io.casehub.examples.typedcontext;

import io.casehub.api.context.CaseContextStore;
import io.casehub.api.context.CaseContextStoreFactory;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Factory that creates AuditingCaseContextStore instances. Tracks all created stores by caseId for
 * test verification.
 */
@ApplicationScoped
public class AuditingCaseContextStoreFactory implements CaseContextStoreFactory {

  private final Map<UUID, Map<String, AuditingCaseContextStore>> storesByCaseId =
      new ConcurrentHashMap<>();

  @Override
  public String id() {
    return "auditing";
  }

  @Override
  public CaseContextStore createStore(String layerName, UUID caseId) {
    AuditingCaseContextStore store = new AuditingCaseContextStore();
    if (caseId != null) {
      storesByCaseId.computeIfAbsent(caseId, k -> new ConcurrentHashMap<>()).put(layerName, store);
    }
    return store;
  }

  public AuditingCaseContextStore getStore(UUID caseId, String layerName) {
    Map<String, AuditingCaseContextStore> layers = storesByCaseId.get(caseId);
    return layers != null ? layers.get(layerName) : null;
  }

  public void reset() {
    storesByCaseId.clear();
  }
}
