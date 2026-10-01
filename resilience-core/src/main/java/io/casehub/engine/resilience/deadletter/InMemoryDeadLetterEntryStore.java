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

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class InMemoryDeadLetterEntryStore implements DeadLetterEntryStore {

  private final Map<String, DeadLetterEntry> store = new ConcurrentHashMap<>();

  @Override
  public DeadLetterEntry save(DeadLetterEntry entry) {
    store.put(entry.deadLetterId(), entry);
    return entry;
  }

  @Override
  public DeadLetterEntry findById(String deadLetterId) {
    return store.get(deadLetterId);
  }

  @Override
  public List<DeadLetterEntry> query(DeadLetterQuery query) {
    return store.values().stream().filter(query.toPredicate()).toList();
  }

  @Override
  public void updateStatus(String deadLetterId, DeadLetterStatus status) {
    DeadLetterEntry entry = store.get(deadLetterId);
    if (entry != null) {
      entry.setStatus(status);
    }
  }

  @Override
  public void incrementReplayAttempts(String deadLetterId) {
    DeadLetterEntry entry = store.get(deadLetterId);
    if (entry != null) {
      entry.incrementReplayAttempts();
    }
  }

  @Override
  public void deleteAll() {
    store.clear();
  }
}
