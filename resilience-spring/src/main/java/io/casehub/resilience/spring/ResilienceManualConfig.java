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
package io.casehub.resilience.spring;

import io.casehub.resilience.deadletter.DeadLetterAutoReplayJob;
import io.casehub.resilience.deadletter.DeadLetterQueue;
import io.casehub.resilience.deadletter.DeadLetterReplayService;
import io.casehub.resilience.poison.PoisonPillDetector;
import java.time.Duration;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.context.annotation.Bean;

@AutoConfiguration
public class ResilienceManualConfig {

  @Bean
  public PoisonPillDetector poisonPillDetector(
      @Value("${casehub.resilience.poison-pill.threshold:5}") int threshold,
      @Value("${casehub.resilience.poison-pill.window:PT10M}") Duration window,
      @Value("${casehub.resilience.poison-pill.quarantine:PT30M}") Duration quarantine) {
    return new PoisonPillDetector(threshold, window, quarantine);
  }

  @Bean
  public DeadLetterAutoReplayJob deadLetterAutoReplayJob(
      DeadLetterQueue dlq,
      DeadLetterReplayService replayService,
      @Value("${casehub.dlq.auto-replay.enabled:false}") boolean enabled,
      @Value("${casehub.dlq.auto-replay.max-attempts:3}") int maxAttempts,
      @Value("${casehub.dlq.auto-replay.delays:PT30M,PT2H,PT8H}") List<Duration> delays) {
    return new DeadLetterAutoReplayJob(dlq, replayService, enabled, maxAttempts, delays);
  }
}
