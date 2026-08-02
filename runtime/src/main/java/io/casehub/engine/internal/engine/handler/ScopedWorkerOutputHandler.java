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

import io.casehub.api.context.CaseContext;
import io.casehub.api.context.ContextLayer;
import io.casehub.api.model.Binding;
import io.casehub.api.model.CaseDefinition;
import io.casehub.api.model.ConflictResolver;
import io.casehub.engine.common.internal.event.CaseContextChangedEvent;
import io.casehub.engine.common.internal.event.EventBusAddresses;
import io.casehub.engine.common.internal.event.ScopedWorkerOutputEvent;
import io.casehub.engine.common.internal.model.CaseInstance;
import io.casehub.engine.common.internal.worker.scope.ScopedWorkerRegistry;
import io.casehub.engine.common.spi.CaseDefinitionRegistry;
import io.smallrye.common.annotation.RunOnVirtualThread;
import io.vertx.mutiny.core.eventbus.EventBus;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.Map;
import org.jboss.logging.Logger;

@ApplicationScoped
public class ScopedWorkerOutputHandler {

  private static final Logger LOG = Logger.getLogger(ScopedWorkerOutputHandler.class);

  @Inject CaseDefinitionRegistry definitionRegistry;
  @Inject ScopedWorkerRegistry scopedWorkerRegistry;
  @Inject EventBus eventBus;

  @io.quarkus.vertx.ConsumeEvent(EventBusAddresses.SCOPED_WORKER_OUTPUT)
  @RunOnVirtualThread
  public void onScopedWorkerOutput(ScopedWorkerOutputEvent event) {
    CaseInstance instance = event.caseInstance();

    if (scopedWorkerRegistry.get(instance.getUuid(), event.bindingName()).isEmpty()) {
      LOG.debugf(
          "Discarding output for terminated scope binding '%s' case %s",
          event.bindingName(), instance.getUuid());
      return;
    }

    CaseDefinition definition = definitionRegistry.getCaseDefinition(instance.getCaseMetaModel());
    String strategy = null;
    if (definition != null && definition.getBindings() != null) {
      Binding binding =
          definition.getBindings().stream()
              .filter(b -> b.getName().equals(event.bindingName()))
              .findFirst()
              .orElse(null);
      if (binding != null) {
        strategy = binding.getConflictResolverStrategy();
      }
    }

    CaseContext caseContext = instance.getCaseContext();
    for (Map.Entry<String, Object> entry : event.output().entrySet()) {
      String key = entry.getKey();
      Object incoming = entry.getValue();
      Object existing = caseContext.get(key);
      Object resolved =
          existing != null ? ConflictResolver.resolve(strategy, key, existing, incoming) : incoming;
      caseContext.set(key, resolved);
    }

    eventBus.publish(
        EventBusAddresses.CONTEXT_CHANGED,
        new CaseContextChangedEvent(
            instance, caseContext.snapshot(), ContextLayer.WORKING, null, null, null));

    LOG.debugf(
        "Applied scoped output for binding '%s' case %s (%d keys)",
        event.bindingName(), instance.getUuid(), event.output().size());
  }
}
