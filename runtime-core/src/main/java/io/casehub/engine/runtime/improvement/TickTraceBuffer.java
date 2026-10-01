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

import io.casehub.api.model.stigmergy.TickTrace;
import io.casehub.engine.common.spi.Resettable;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.ArrayDeque;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@ApplicationScoped
public class TickTraceBuffer implements Resettable {

  private static final int DEFAULT_CAPACITY = 100;

  private final ConcurrentHashMap<UUID, ArrayDeque<TickTrace>> buffers = new ConcurrentHashMap<>();

  public void record(TickTrace trace) {
    var deque = buffers.computeIfAbsent(trace.caseId(), k -> new ArrayDeque<>(DEFAULT_CAPACITY));
    synchronized (deque) {
      if (deque.size() >= DEFAULT_CAPACITY) {
        deque.pollFirst();
      }
      deque.addLast(trace);
    }
  }

  public List<TickTrace> recent(UUID caseId, int limit) {
    var deque = buffers.get(caseId);
    if (deque == null) return List.of();
    synchronized (deque) {
      return deque.stream()
          .sorted(Comparator.comparing(TickTrace::timestamp).reversed())
          .limit(limit)
          .toList();
    }
  }

  @Override
  public void reset() {
    buffers.clear();
  }
}
