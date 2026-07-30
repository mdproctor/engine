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
    sessions.entrySet().removeIf(e -> {
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
