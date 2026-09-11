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
package io.casehub.engine.common.spi.recovery;

import jakarta.enterprise.context.ApplicationScoped;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

@ApplicationScoped
public class CompoundLockRegistry {
  private final ConcurrentHashMap<String, ReentrantLock> locks = new ConcurrentHashMap<>();

  public ReentrantLock getLock(UUID caseId, String compoundId) {
    return locks.computeIfAbsent(caseId + ":" + compoundId, k -> new ReentrantLock());
  }

  public void cleanForCase(UUID caseId) {
    locks.keySet().removeIf(k -> k.startsWith(caseId + ":"));
  }

  public void cleanForCompound(UUID caseId, String compoundId) {
    locks.remove(caseId + ":" + compoundId);
  }
}
