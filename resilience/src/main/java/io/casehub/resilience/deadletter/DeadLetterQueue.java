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

import io.casehub.api.model.RetryState;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * In-memory Dead Letter Queue. Stores {@link DeadLetterEntry} records for worker executions that
 * have exhausted all retry attempts. Supports query by status/worker/time range, manual discard,
 * and replay acknowledgement.
 *
 * <p>Thread-safe. Current implementation is in-memory; a persistent-storage hook (Redis, Hibernate)
 * can be added by providing an alternative CDI bean.
 */
@ApplicationScoped
public class DeadLetterQueue {

  private final Map<String, DeadLetterEntry> store = new ConcurrentHashMap<>();

  /**
   * Adds a new entry to the queue with {@link DeadLetterStatus#PENDING_REVIEW}.
   *
   * @param caseId the case whose worker failed
   * @param workerId the failing worker name
   * @param idempotencyHash the input hash used for deduplication
   * @param inputContext the original worker input data
   * @param retryState the retry attempt history
   * @return the newly created entry
   */
  public DeadLetterEntry add(
      UUID caseId,
      String workerId,
      String idempotencyHash,
      Map<String, Object> inputContext,
      RetryState retryState) {
    String id = UUID.randomUUID().toString();
    DeadLetterEntry entry =
        new DeadLetterEntry(id, caseId, workerId, idempotencyHash, inputContext, retryState);
    store.put(id, entry);
    return entry;
  }

  /**
   * Returns all entries matching the given query. Filters are AND-combined.
   *
   * @param query the filter specification
   * @return matching entries (unordered)
   */
  public List<DeadLetterEntry> query(DeadLetterQuery query) {
    return store.values().stream().filter(query.toPredicate()).collect(Collectors.toList());
  }

  /**
   * Returns the entry with the given ID, or {@code null} if not found. O(1) lookup.
   *
   * @param deadLetterId the entry ID
   */
  public DeadLetterEntry findById(String deadLetterId) {
    return store.get(deadLetterId);
  }

  /**
   * Marks the entry as {@link DeadLetterStatus#DISCARDED}. No-op if the ID is not found.
   *
   * @param deadLetterId the entry to discard
   */
  public void discard(String deadLetterId) {
    DeadLetterEntry entry = store.get(deadLetterId);
    if (entry != null) {
      entry.setStatus(DeadLetterStatus.DISCARDED);
    }
  }

  /**
   * Marks the entry as {@link DeadLetterStatus#REPLAYED} after a successful replay submission.
   * No-op if the ID is not found.
   *
   * @param deadLetterId the entry that was replayed
   */
  public void markReplayed(String deadLetterId) {
    DeadLetterEntry entry = store.get(deadLetterId);
    if (entry != null) {
      entry.setStatus(DeadLetterStatus.REPLAYED);
    }
  }

  /** Returns the total number of entries in the queue regardless of status. */
  public int size() {
    return store.size();
  }

  /** Removes all entries. Intended for test isolation only. */
  public void clear() {
    store.clear();
  }
}
