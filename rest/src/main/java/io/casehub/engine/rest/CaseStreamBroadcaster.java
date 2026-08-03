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
package io.casehub.engine.rest;

import io.casehub.engine.common.spi.event.CaseContextUpdatedEvent;
import io.casehub.engine.common.spi.event.PlanItemStateChangedEvent;
import io.casehub.engine.rest.dto.CaseStreamEvent;
import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.operators.multi.processors.BroadcastProcessor;
import io.smallrye.mutiny.subscription.BackPressureFailure;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.ObservesAsync;
import java.util.UUID;

@ApplicationScoped
public class CaseStreamBroadcaster {

  private final BroadcastProcessor<CaseStreamEvent> processor = BroadcastProcessor.create();

  void onPlanItemChanged(@ObservesAsync PlanItemStateChangedEvent event) {
    try {
      processor.onNext(CaseStreamEvent.planItem(event));
    } catch (BackPressureFailure ignored) {
    }
  }

  void onContextUpdated(@ObservesAsync CaseContextUpdatedEvent event) {
    try {
      processor.onNext(CaseStreamEvent.context(event));
    } catch (BackPressureFailure ignored) {
    }
  }

  public Multi<CaseStreamEvent> stream(UUID caseId) {
    return processor.toHotStream().filter(e -> caseId.equals(e.caseId()));
  }
}
