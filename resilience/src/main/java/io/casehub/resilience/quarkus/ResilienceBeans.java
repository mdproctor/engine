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
package io.casehub.resilience.quarkus;

import io.casehub.api.spi.event.EventDispatcher;
import io.casehub.engine.common.qualifier.CrossTenant;
import io.casehub.engine.common.spi.CaseDefinitionRegistry;
import io.casehub.engine.common.spi.CaseInstanceRepository;
import io.casehub.engine.common.spi.CrossTenantCaseInstanceRepository;
import io.casehub.engine.common.spi.CrossTenantEventLogRepository;
import io.casehub.engine.common.spi.cache.CaseInstanceCache;
import io.casehub.resilience.conflict.LastWriterWinsConflictResolver;
import io.casehub.resilience.deadletter.DeadLetterAutoReplayJob;
import io.casehub.resilience.deadletter.DeadLetterEventHandler;
import io.casehub.resilience.deadletter.DeadLetterQueue;
import io.casehub.resilience.deadletter.DeadLetterReplayService;
import io.casehub.resilience.poison.PoisonPillDetector;
import io.casehub.resilience.poison.PoisonPillWorkerExecutionGuard;
import io.casehub.resilience.timeout.CaseTimeoutEnforcer;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Alternative;
import jakarta.enterprise.inject.Produces;
import java.time.Duration;
import java.util.List;
import org.eclipse.microprofile.config.inject.ConfigProperty;

@ApplicationScoped
public class ResilienceBeans {

  @Produces
  @ApplicationScoped
  LastWriterWinsConflictResolver lastWriterWinsConflictResolver() {
    return new LastWriterWinsConflictResolver();
  }

  @Produces
  @ApplicationScoped
  DeadLetterQueue deadLetterQueue() {
    return new DeadLetterQueue();
  }

  @Produces
  @ApplicationScoped
  DeadLetterEventHandler deadLetterEventHandler(DeadLetterQueue dlq) {
    return new DeadLetterEventHandler(dlq);
  }

  @Produces
  @ApplicationScoped
  PoisonPillDetector poisonPillDetector(
      @ConfigProperty(name = "casehub.resilience.poison-pill.threshold", defaultValue = "5")
          int threshold,
      @ConfigProperty(name = "casehub.resilience.poison-pill.window", defaultValue = "PT10M")
          Duration window,
      @ConfigProperty(name = "casehub.resilience.poison-pill.quarantine", defaultValue = "PT30M")
          Duration quarantine) {
    return new PoisonPillDetector(threshold, window, quarantine);
  }

  @Produces
  @Alternative
  @Priority(10)
  PoisonPillWorkerExecutionGuard poisonPillWorkerExecutionGuard(PoisonPillDetector detector) {
    return new PoisonPillWorkerExecutionGuard(detector);
  }

  @Produces
  @ApplicationScoped
  DeadLetterReplayService deadLetterReplayService(
      DeadLetterQueue dlq,
      @CrossTenant CrossTenantEventLogRepository eventLogRepository,
      @CrossTenant CrossTenantCaseInstanceRepository caseInstanceRepository,
      CaseDefinitionRegistry caseDefinitionRegistry,
      EventDispatcher eventDispatcher) {
    return new DeadLetterReplayService(
        dlq, eventLogRepository, caseInstanceRepository, caseDefinitionRegistry, eventDispatcher);
  }

  @Produces
  @ApplicationScoped
  DeadLetterAutoReplayJob deadLetterAutoReplayJob(
      DeadLetterQueue dlq,
      DeadLetterReplayService replayService,
      @ConfigProperty(name = "casehub.dlq.auto-replay.enabled", defaultValue = "false")
          boolean enabled,
      @ConfigProperty(name = "casehub.dlq.auto-replay.max-attempts", defaultValue = "3")
          int maxAttempts,
      @ConfigProperty(name = "casehub.dlq.auto-replay.delays", defaultValue = "PT30M,PT2H,PT8H")
          List<Duration> delays) {
    return new DeadLetterAutoReplayJob(dlq, replayService, enabled, maxAttempts, delays);
  }

  @Produces
  @ApplicationScoped
  CaseTimeoutEnforcer caseTimeoutEnforcer(
      CaseInstanceCache cache,
      EventDispatcher eventDispatcher,
      CaseInstanceRepository caseInstanceRepository) {
    return new CaseTimeoutEnforcer(cache, eventDispatcher, caseInstanceRepository);
  }
}
