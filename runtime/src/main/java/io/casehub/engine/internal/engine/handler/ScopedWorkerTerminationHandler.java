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
package io.casehub.engine.internal.engine.handler;

import io.casehub.engine.common.internal.event.CompoundCompletedEvent;
import io.casehub.engine.common.internal.event.EventBusAddresses;
import io.casehub.engine.common.internal.worker.scope.ScopedWorkerRegistry;
import io.quarkus.vertx.ConsumeEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.Set;
import org.jboss.logging.Logger;

@ApplicationScoped
public class ScopedWorkerTerminationHandler {

  private static final Logger LOG = Logger.getLogger(ScopedWorkerTerminationHandler.class);

  @Inject ScopedWorkerRegistry scopedWorkerRegistry;

  @ConsumeEvent(EventBusAddresses.COMPOUND_COMPLETED)
  @io.smallrye.common.annotation.RunOnVirtualThread
  public void onCompoundCompleted(CompoundCompletedEvent event) {
    Set<String> scopedBindings = event.scopedBindingNames();
    if (scopedBindings == null || scopedBindings.isEmpty()) {
      return;
    }

    scopedWorkerRegistry.terminateByScope(event.caseId(), event.compoundId(), scopedBindings);
    LOG.debugf(
        "Terminated scoped workers for compound '%s' case %s",
        event.compoundName(), event.caseId());
  }
}
