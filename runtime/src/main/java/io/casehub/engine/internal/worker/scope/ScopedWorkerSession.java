package io.casehub.engine.internal.worker.scope;

import io.casehub.api.model.LifecycleScope;
import io.casehub.api.model.Participation;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicReference;

public sealed interface ScopedWorkerSession
    permits ScopedWorkerSession.Persistent, ScopedWorkerSession.Reinvoked {

  String bindingName();

  UUID caseId();

  String planItemId();

  LifecycleScope scope();

  Participation participation();

  record Persistent(
      String bindingName,
      UUID caseId,
      String planItemId,
      LifecycleScope scope,
      Participation participation,
      BlockingQueue<ContextEvent> mailbox,
      Future<?> workerThread)
      implements ScopedWorkerSession {}

  record Reinvoked(
      String bindingName,
      UUID caseId,
      String planItemId,
      LifecycleScope scope,
      Participation participation,
      AtomicReference<Map<String, Object>> accumulatedState)
      implements ScopedWorkerSession {}
}
