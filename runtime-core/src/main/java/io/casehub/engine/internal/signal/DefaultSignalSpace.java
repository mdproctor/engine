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
package io.casehub.engine.internal.signal;

import io.casehub.api.engine.SignalSpace;
import io.casehub.api.model.signal.PerceivedSignal;
import io.casehub.api.model.signal.SignalConfig;
import io.casehub.engine.common.internal.signal.SignalRegistry;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;

public class DefaultSignalSpace implements SignalSpace {

  private final SignalRegistry signalRegistry;
  private final UUID caseId;
  private final String workerName;
  private final SignalConfig config;

  public DefaultSignalSpace(
      SignalRegistry signalRegistry, UUID caseId, String workerName, SignalConfig config) {
    this.signalRegistry = signalRegistry;
    this.caseId = caseId;
    this.workerName = workerName;
    this.config = config;
  }

  @Override
  public void deposit(String name, double strength) {
    deposit(name, strength, config.defaultHalfLife());
  }

  @Override
  public void deposit(String name, double strength, Duration halfLife) {
    signalRegistry.deposit(
        caseId, name, strength, halfLife, workerName, config.maxSignalsPerCase());
  }

  @Override
  public Map<String, PerceivedSignal> perceive() {
    return signalRegistry.perceive(caseId, config.effectiveZeroThreshold());
  }
}
