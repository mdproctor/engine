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
package io.casehub.engine.common.internal.observation;

import com.fasterxml.jackson.databind.JsonNode;
import io.casehub.api.spi.observation.ContextSnapshot;
import io.casehub.engine.common.spi.Resettable;
import jakarta.enterprise.context.ApplicationScoped;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@ApplicationScoped
public class ContextHistoryBuffer implements Resettable {

  private final ConcurrentHashMap<UUID, JsonNode> lastSnapshots = new ConcurrentHashMap<>();
  private final ConcurrentHashMap<UUID, LinkedList<ContextSnapshot>> histories =
      new ConcurrentHashMap<>();

  public Set<String> computeChangedKeys(UUID caseId, JsonNode currentSnapshot) {
    JsonNode previous = lastSnapshots.put(caseId, currentSnapshot);
    if (previous == null) {
      Set<String> keys = new HashSet<>();
      currentSnapshot.fieldNames().forEachRemaining(keys::add);
      return keys;
    }
    Set<String> changed = new HashSet<>();
    currentSnapshot
        .fieldNames()
        .forEachRemaining(
            field -> {
              JsonNode prev = previous.get(field);
              if (prev == null || !prev.equals(currentSnapshot.get(field))) {
                changed.add(field);
              }
            });
    previous
        .fieldNames()
        .forEachRemaining(
            field -> {
              if (currentSnapshot.get(field) == null) {
                changed.add(field);
              }
            });
    return changed;
  }

  public Map<String, JsonNode> extractChangedValues(JsonNode snapshot, Set<String> changedKeys) {
    Map<String, JsonNode> values = new LinkedHashMap<>();
    for (String key : changedKeys) {
      JsonNode value = snapshot.get(key);
      if (value != null) {
        values.put(key, value);
      }
    }
    return values;
  }

  public void record(UUID caseId, ContextSnapshot snapshot) {
    histories.computeIfAbsent(caseId, k -> new LinkedList<>()).addLast(snapshot);
  }

  public List<ContextSnapshot> getHistory(UUID caseId, int maxEntries, Duration maxAge) {
    var history = histories.get(caseId);
    if (history == null) {
      return List.of();
    }
    Instant cutoff = Instant.now().minus(maxAge);
    List<ContextSnapshot> result = new ArrayList<>();
    synchronized (history) {
      var it = history.descendingIterator();
      while (it.hasNext() && result.size() < maxEntries) {
        ContextSnapshot snap = it.next();
        if (snap.timestamp().isBefore(cutoff)) {
          break;
        }
        result.add(snap);
      }
    }
    Collections.reverse(result);
    return result;
  }

  public void evict(UUID caseId) {
    histories.remove(caseId);
    lastSnapshots.remove(caseId);
  }

  public void evictExpired(UUID caseId, int maxEntries, Duration maxAge) {
    var history = histories.get(caseId);
    if (history == null) {
      return;
    }
    Instant cutoff = Instant.now().minus(maxAge);
    synchronized (history) {
      while (history.size() > maxEntries) {
        history.removeFirst();
      }
      while (!history.isEmpty() && history.getFirst().timestamp().isBefore(cutoff)) {
        history.removeFirst();
      }
    }
  }

  @Override
  public void reset() {
    histories.clear();
    lastSnapshots.clear();
  }
}
