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
package io.casehub.engine.internal.engine.handler;

import com.fasterxml.jackson.databind.JsonNode;
import io.casehub.api.context.CaseContext;
import io.casehub.api.model.Binding;
import io.casehub.api.model.CaseDefinition;
import io.casehub.api.model.ConflictResolver;
import io.casehub.api.spi.ContextDiffStrategy;
import io.casehub.engine.common.internal.model.CaseInstance;
import io.casehub.engine.common.spi.CaseDefinitionRegistry;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

@ApplicationScoped
public class ContextOutputApplier {

  private final ConcurrentHashMap<UUID, ReentrantLock> locks = new ConcurrentHashMap<>();
  @Inject CaseDefinitionRegistry caseDefinitionRegistry;
  @Inject ContextDiffStrategy contextDiffStrategy;

  public JsonNode apply(CaseInstance instance, Map<String, Object> output, String bindingName) {
    if (output == null || output.isEmpty()) {
      return null;
    }
    ReentrantLock lock = locks.computeIfAbsent(instance.getUuid(), k -> new ReentrantLock());
    lock.lock();
    try {
      JsonNode contextBefore = instance.getCaseContext().snapshot().asJsonNode();

      Binding binding = findBindingByName(instance, bindingName);
      String strategy = binding != null ? binding.getConflictResolverStrategy() : null;
      CaseContext context = instance.getCaseContext();

      if ("FAIL".equals(strategy)) {
        for (String key : output.keySet()) {
          if (context.get(key) != null) {
            throw new IllegalStateException(
                "FAIL strategy: key '" + key + "' already exists — rejecting entire output");
          }
        }
      }

      for (Map.Entry<String, Object> entry : output.entrySet()) {
        String key = entry.getKey();
        Object incoming = entry.getValue();
        Object existing = context.get(key);
        Object resolved =
            existing != null
                ? ConflictResolver.resolve(strategy, key, existing, incoming)
                : incoming;
        context.set(key, resolved);
      }

      JsonNode contextAfter = instance.getCaseContext().asJsonNode();
      return contextDiffStrategy.compute(contextBefore, contextAfter);
    } finally {
      lock.unlock();
    }
  }

  public void evict(UUID caseId) {
    locks.remove(caseId);
  }

  Binding findBindingByName(CaseInstance instance, String bindingName) {
    CaseDefinition definition =
        caseDefinitionRegistry.getCaseDefinition(instance.getCaseMetaModel());
    if (definition == null || definition.getBindings() == null || bindingName == null) {
      return null;
    }
    return definition.getBindings().stream()
        .filter(b -> b.getName().equals(bindingName))
        .findFirst()
        .orElse(null);
  }
}
