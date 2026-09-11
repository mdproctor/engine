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
package io.casehub.engine.work.cloudevent.quarkus;

import io.casehub.engine.common.spi.HumanTaskScheduler;
import io.casehub.engine.common.spi.PlanItemStore;
import io.casehub.engine.planning.completion.GateCompletionApplier;
import io.casehub.engine.planning.completion.PlanItemCompletionApplier;
import io.casehub.engine.planning.registry.BlackboardRegistry;
import io.casehub.engine.work.cloudevent.CloudEventActionGateScheduler;
import io.casehub.engine.work.cloudevent.CloudEventHumanTaskScheduler;
import io.casehub.engine.work.cloudevent.CloudEventJudgmentScheduler;
import io.casehub.engine.work.cloudevent.WorkIntegrationConflictDetector;
import io.casehub.engine.work.cloudevent.WorkItemLifecycleCloudEventConsumer;
import io.cloudevents.CloudEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Event;
import jakarta.enterprise.inject.Instance;
import jakarta.enterprise.inject.Produces;

@ApplicationScoped
public class WorkCloudEventBeans {

  @Produces
  @ApplicationScoped
  CloudEventActionGateScheduler cloudEventActionGateScheduler(Event<CloudEvent> emitter) {
    return new CloudEventActionGateScheduler(e -> emitter.fireAsync(e));
  }

  @Produces
  @ApplicationScoped
  WorkIntegrationConflictDetector workIntegrationConflictDetector(
      Instance<HumanTaskScheduler> schedulers) {
    return new WorkIntegrationConflictDetector(schedulers.stream().toList());
  }

  @Produces
  @ApplicationScoped
  WorkItemLifecycleCloudEventConsumer workItemLifecycleCloudEventConsumer(
      PlanItemCompletionApplier planItemApplier,
      GateCompletionApplier gateApplier,
      BlackboardRegistry blackboardRegistry) {
    return new WorkItemLifecycleCloudEventConsumer(
        planItemApplier, gateApplier, blackboardRegistry);
  }

  @Produces
  @ApplicationScoped
  @SuppressWarnings("removal")
  CloudEventHumanTaskScheduler cloudEventHumanTaskScheduler(
      BlackboardRegistry registry, PlanItemStore planItemStore, Event<CloudEvent> emitter) {
    return new CloudEventHumanTaskScheduler(registry, planItemStore, e -> emitter.fireAsync(e));
  }

  @Produces
  @ApplicationScoped
  CloudEventJudgmentScheduler cloudEventJudgmentScheduler(
      BlackboardRegistry registry, PlanItemStore planItemStore, Event<CloudEvent> emitter) {
    return new CloudEventJudgmentScheduler(registry, planItemStore, e -> emitter.fireAsync(e));
  }
}
