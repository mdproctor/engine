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
package io.casehub.blackboard.handler;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.casehub.blackboard.event.BlackboardEventBusAddresses;
import io.casehub.blackboard.event.StageActivatedEvent;
import io.casehub.engine.common.internal.model.CaseInstance;
import io.casehub.engine.common.spi.cache.CaseInstanceCache;
import io.quarkus.vertx.ConsumeEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.Map;
import java.util.Set;
import org.jboss.logging.Logger;

/**
 * Clears {@code _diagnostics} entries for a stage's bindings when a repeatable stage resets.
 * Without this, excluded agents from iteration N carry over to iteration N+1, incorrectly
 * preventing agents from being dispatched for new work. Refs casehubio/engine#517.
 */
@ApplicationScoped
public class StageResetOutcomesCleaner {

  private static final Logger LOG = Logger.getLogger(StageResetOutcomesCleaner.class);
  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

  @Inject CaseInstanceCache caseInstanceCache;

  @SuppressWarnings("unchecked")
  @ConsumeEvent(value = BlackboardEventBusAddresses.STAGE_ACTIVATED, blocking = true)
  public void onStageActivated(StageActivatedEvent event) {
    if (event.instanceIndex() == 0) {
      return;
    }

    final Set<String> bindingNames = event.stage().getContainedBindingNames();
    if (bindingNames.isEmpty()) {
      return;
    }

    final CaseInstance instance = caseInstanceCache.get(event.caseId());
    if (instance == null) {
      LOG.warnf(
          "CaseInstance not found for caseId=%s during stage reset outcomes cleanup",
          event.caseId());
      return;
    }

    final Map<String, Object> existingOutcomes =
        (Map<String, Object>) instance.getCaseContext().get("_diagnostics");
    if (existingOutcomes == null) {
      return;
    }

    final ObjectNode outcomesRoot = MAPPER.valueToTree(existingOutcomes).deepCopy();
    boolean modified = false;
    for (String bindingName : bindingNames) {
      if (outcomesRoot.has(bindingName)) {
        outcomesRoot.remove(bindingName);
        modified = true;
      }
    }

    if (modified) {
      final Map<String, Object> outcomesMap = MAPPER.convertValue(outcomesRoot, MAP_TYPE);
      instance.getCaseContext().set("_diagnostics", outcomesMap);
      LOG.infof(
          "Cleared _diagnostics for bindings %s on stage '%s' reset (instance %d), caseId=%s",
          bindingNames, event.stage().getName(), event.instanceIndex(), event.caseId());
    }
  }
}
