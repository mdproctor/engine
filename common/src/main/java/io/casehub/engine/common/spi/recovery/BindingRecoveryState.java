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

import io.casehub.api.model.RecoveryLevel;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

public class BindingRecoveryState {
  private volatile RecoveryLevel currentLevel;
  private final AtomicInteger retryCount = new AtomicInteger(0);
  private final Set<String> excludedAgents = ConcurrentHashMap.newKeySet();

  public RecoveryLevel currentLevel() {
    return currentLevel;
  }

  public void setCurrentLevel(RecoveryLevel level) {
    this.currentLevel = level;
  }

  public int retryCount() {
    return retryCount.get();
  }

  public int incrementRetryCount() {
    return retryCount.incrementAndGet();
  }

  public Set<String> excludedAgents() {
    return Set.copyOf(excludedAgents);
  }

  public void excludeAgent(String agentName) {
    excludedAgents.add(agentName);
  }
}
