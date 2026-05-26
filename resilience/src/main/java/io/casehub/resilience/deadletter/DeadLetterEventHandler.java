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
package io.casehub.resilience.deadletter;

import io.casehub.engine.common.internal.event.EventBusAddresses;
import io.casehub.engine.common.internal.event.WorkerRetriesExhaustedEvent;
import io.quarkus.vertx.ConsumeEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.Map;
import org.jboss.logging.Logger;

/**
 * Listens for {@link EventBusAddresses#WORKER_RETRIES_EXHAUSTED} events and routes the failing
 * execution into the {@link DeadLetterQueue}. Runs alongside the engine's {@code
 * WorkerRetriesExhaustedEventHandler} — both handlers receive the same event independently.
 */
@ApplicationScoped
public class DeadLetterEventHandler {

  private static final Logger LOG = Logger.getLogger(DeadLetterEventHandler.class);

  @Inject DeadLetterQueue deadLetterQueue;

  @ConsumeEvent(value = EventBusAddresses.WORKER_RETRIES_EXHAUSTED)
  public void onWorkerRetriesExhausted(WorkerRetriesExhaustedEvent event) {
    LOG.infof(
        "Routing exhausted worker to DLQ: caseId=%s, workerId=%s",
        event.caseId(), event.workerId());

    deadLetterQueue.add(
        event.caseId(),
        event.workerId(),
        event.idempotency(),
        // Input context is not carried in the event itself — the DLQ entry stores the
        // idempotency hash so the full input can be recovered from EventLog on replay.
        Map.of("idempotencyHash", event.idempotency()));
  }
}
