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
package io.casehub.engine.internal.worker.scope;

import jakarta.enterprise.context.ApplicationScoped;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@ApplicationScoped
public class ScopedWorkerRegistry {

  private final ConcurrentHashMap<ScopeKey, ScopedWorkerSession> sessions =
      new ConcurrentHashMap<>();

  public Optional<ScopedWorkerSession> get(UUID caseId, String bindingName) {
    return Optional.ofNullable(sessions.get(new ScopeKey(caseId, bindingName)));
  }

  public void register(ScopeKey key, ScopedWorkerSession session) {
    ScopedWorkerSession previous = sessions.put(key, session);
    if (previous instanceof ScopedWorkerSession.Persistent p) {
      p.mailbox().offer(ContextEvent.SHUTDOWN);
    }
  }

  public void terminateByCase(UUID caseId) {
    sessions
        .entrySet()
        .removeIf(
            e -> {
              if (e.getKey().caseId().equals(caseId)) {
                terminateSession(e.getValue());
                return true;
              }
              return false;
            });
  }

  public void terminateByScope(UUID caseId, String compoundId, Set<String> ownedBindings) {
    for (String bindingName : ownedBindings) {
      ScopedWorkerSession removed = sessions.remove(new ScopeKey(caseId, bindingName));
      if (removed != null) {
        terminateSession(removed);
      }
    }
  }

  private void terminateSession(ScopedWorkerSession session) {
    if (session instanceof ScopedWorkerSession.Persistent p) {
      p.mailbox().offer(ContextEvent.SHUTDOWN);
    }
  }

  public record ScopeKey(UUID caseId, String bindingName) {}
}
