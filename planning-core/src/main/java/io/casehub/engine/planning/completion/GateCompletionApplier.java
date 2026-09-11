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
package io.casehub.engine.planning.completion;

import io.casehub.api.model.TaskStatus;
import io.casehub.api.spi.event.EventDispatcher;
import io.casehub.engine.common.internal.event.ActionGateApprovedEvent;
import io.casehub.engine.common.internal.event.ActionGateExpiredEvent;
import io.casehub.engine.common.internal.event.ActionGateRejectedEvent;
import java.util.UUID;
import org.jboss.logging.Logger;
import org.jspecify.annotations.Nullable;

public class GateCompletionApplier {

  private static final Logger LOG = Logger.getLogger(GateCompletionApplier.class);

  private final EventDispatcher eventDispatcher;

  public GateCompletionApplier(EventDispatcher eventDispatcher) {
    this.eventDispatcher = eventDispatcher;
  }

  public void apply(
      UUID caseId,
      String tenancyId,
      long gateId,
      TaskStatus status,
      @Nullable String resolution,
      @Nullable String actorId) {
    switch (status) {
      case COMPLETED ->
          eventDispatcher.dispatch(
              new ActionGateApprovedEvent(caseId, tenancyId, gateId, resolution, actorId, null));
      case REJECTED, CANCELLED ->
          eventDispatcher.dispatch(
              new ActionGateRejectedEvent(caseId, tenancyId, gateId, resolution, actorId));
      case FAULTED ->
          eventDispatcher.dispatch(new ActionGateExpiredEvent(caseId, tenancyId, gateId));
      default ->
          LOG.warnf("Unsupported gate status %s for caseId=%s gateId=%d", status, caseId, gateId);
    }
  }
}
