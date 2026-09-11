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
package io.casehub.engine.common.internal.judgment;

import io.casehub.engine.common.spi.JudgmentNodeResult;
import io.casehub.engine.common.spi.JudgmentResponse;
import io.casehub.engine.common.spi.JudgmentScheduleRequest;
import io.casehub.engine.common.spi.JudgmentScheduler;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.jboss.logging.Logger;

/**
 * Blocking executor for judgment yields within DAG/SWF threads. Registers a per-judgment queue,
 * dispatches through {@link JudgmentScheduler}, and blocks the virtual thread until the judgment
 * handlers enqueue a result.
 *
 * <p>Per-cycle timeout resets on each {@link JudgmentNodeResult.ReYielded} — escalation re-yields
 * extend the deadline without accumulating.
 *
 * <p>Refs engine#1000.
 */
@ApplicationScoped
public class JudgmentNodeExecutor {

  private static final Logger LOG = Logger.getLogger(JudgmentNodeExecutor.class);

  private final ConcurrentHashMap<String, BlockingQueue<JudgmentNodeResult>> pending =
      new ConcurrentHashMap<>();

  @Inject Instance<JudgmentScheduler> judgmentScheduler;

  public JudgmentResponse execute(JudgmentScheduleRequest request, Duration perCycleTimeout) {
    String key = key(request.caseId(), request.bindingName());
    BlockingQueue<JudgmentNodeResult> queue = new LinkedBlockingQueue<>();
    BlockingQueue<JudgmentNodeResult> existing = pending.putIfAbsent(key, queue);
    if (existing != null) {
      throw new IllegalStateException("Concurrent judgment execution for the same binding: " + key);
    }
    try {
      if (judgmentScheduler.isResolvable()) {
        judgmentScheduler.get().schedule(request);
      } else {
        throw new IllegalStateException(
            "No JudgmentScheduler available to dispatch judgment for binding '"
                + request.bindingName()
                + "'");
      }

      while (true) {
        JudgmentNodeResult result = queue.poll(perCycleTimeout.toMillis(), TimeUnit.MILLISECONDS);
        if (result == null) {
          throw new JudgmentTimeoutException(request.bindingName(), perCycleTimeout);
        }
        switch (result) {
          case JudgmentNodeResult.Completed c -> {
            return c.response();
          }
          case JudgmentNodeResult.ReYielded ignored -> {
            LOG.debugf(
                "Judgment re-yielded, resetting timeout: caseId=%s binding=%s",
                request.caseId(), request.bindingName());
          }
          case JudgmentNodeResult.Faulted f -> {
            throw new JudgmentFaultException(request.bindingName(), f.reason());
          }
        }
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new JudgmentFaultException(
          request.bindingName(), "Interrupted while awaiting judgment");
    } finally {
      pending.remove(key);
    }
  }

  public void enqueue(UUID caseId, String bindingName, JudgmentNodeResult result) {
    String key = key(caseId, bindingName);
    BlockingQueue<JudgmentNodeResult> queue = pending.get(key);
    if (queue != null) {
      queue.offer(result);
    }
  }

  public boolean hasPending(UUID caseId, String bindingName) {
    return pending.containsKey(key(caseId, bindingName));
  }

  private static String key(UUID caseId, String bindingName) {
    return caseId + ":" + bindingName;
  }
}
