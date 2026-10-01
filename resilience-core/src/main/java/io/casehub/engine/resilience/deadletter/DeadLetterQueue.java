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
package io.casehub.engine.resilience.deadletter;

import io.casehub.api.model.RetryState;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class DeadLetterQueue {

  private final DeadLetterEntryStore store;

  public DeadLetterQueue(DeadLetterEntryStore store) {
    this.store = store;
  }

  public DeadLetterEntry add(
      UUID caseId,
      String workerId,
      String idempotencyHash,
      Map<String, Object> inputContext,
      RetryState retryState) {
    String id = UUID.randomUUID().toString();
    DeadLetterEntry entry =
        new DeadLetterEntry(id, caseId, workerId, idempotencyHash, inputContext, retryState);
    return store.save(entry);
  }

  public List<DeadLetterEntry> query(DeadLetterQuery query) {
    return store.query(query);
  }

  public DeadLetterEntry findById(String deadLetterId) {
    return store.findById(deadLetterId);
  }

  public void discard(String deadLetterId) {
    store.updateStatus(deadLetterId, DeadLetterStatus.DISCARDED);
  }

  public void markReplayed(String deadLetterId) {
    store.updateStatus(deadLetterId, DeadLetterStatus.REPLAYED);
  }

  public int size() {
    return store.query(DeadLetterQuery.all()).size();
  }

  public void clear() {
    store.deleteAll();
  }
}
