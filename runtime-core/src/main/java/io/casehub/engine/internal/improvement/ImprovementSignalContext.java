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
package io.casehub.engine.internal.improvement;

import io.casehub.api.model.stigmergy.ImprovementRequest;
import io.casehub.engine.common.spi.Resettable;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@ApplicationScoped
public class ImprovementSignalContext implements Resettable {

  private final ConcurrentHashMap<UUID, ConcurrentHashMap<String, ImprovementRequest>> contexts =
      new ConcurrentHashMap<>();

  public void register(UUID caseId, String signalName, ImprovementRequest request) {
    contexts.computeIfAbsent(caseId, k -> new ConcurrentHashMap<>()).put(signalName, request);
  }

  public Optional<ImprovementRequest> get(UUID caseId, String signalName) {
    var caseContexts = contexts.get(caseId);
    if (caseContexts == null) {
      return Optional.empty();
    }
    return Optional.ofNullable(caseContexts.get(signalName));
  }

  public void evictByCase(UUID caseId) {
    contexts.remove(caseId);
  }

  @Override
  public void reset() {
    contexts.clear();
  }
}
