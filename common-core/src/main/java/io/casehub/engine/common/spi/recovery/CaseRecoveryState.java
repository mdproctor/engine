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

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class CaseRecoveryState {
  private final Map<String, BindingRecoveryState> bindingStates = new ConcurrentHashMap<>();
  private final Set<String> recoveryAdaptedCompounds = ConcurrentHashMap.newKeySet();
  private volatile boolean replanAttempted;

  public BindingRecoveryState getOrCreate(String bindingName) {
    return bindingStates.computeIfAbsent(bindingName, k -> new BindingRecoveryState());
  }

  public void markCompoundAdapted(String compoundId) {
    recoveryAdaptedCompounds.add(compoundId);
  }

  public boolean isCompoundAdapted(String compoundId) {
    return recoveryAdaptedCompounds.contains(compoundId);
  }

  public void markReplanAttempted() {
    replanAttempted = true;
  }

  public boolean isReplanAttempted() {
    return replanAttempted;
  }

  public Map<String, BindingRecoveryState> bindingStates() {
    return Map.copyOf(bindingStates);
  }

  public Set<String> recoveryAdaptedCompounds() {
    return Set.copyOf(recoveryAdaptedCompounds);
  }
}
