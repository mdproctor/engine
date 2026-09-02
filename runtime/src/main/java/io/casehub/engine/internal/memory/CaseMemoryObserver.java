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
package io.casehub.engine.internal.memory;

import io.casehub.engine.common.spi.event.CaseLifecycleEvent;
import io.casehub.memory.runtime.MemoryEmitter;
import io.casehub.neocortex.memory.MemoryDomain;
import io.casehub.neocortex.memory.MemoryInput;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.ObservesAsync;
import jakarta.inject.Inject;
import java.util.Map;
import java.util.Set;

@ApplicationScoped
public class CaseMemoryObserver {

  private static final MemoryDomain DOMAIN = new MemoryDomain("case-lifecycle");

  private static final Set<String> CAPTURED_EVENTS =
      Set.of("CaseCompleted", "CaseCancelled", "CaseFailed");

  private final MemoryEmitter emitter;

  @Inject
  public CaseMemoryObserver(final MemoryEmitter emitter) {
    this.emitter = emitter;
  }

  public void onCaseLifecycleEvent(@ObservesAsync final CaseLifecycleEvent event) {
    if (!CAPTURED_EVENTS.contains(event.eventType())) {
      return;
    }

    final String caseIdStr = event.caseId().toString();
    final Map<String, String> attrs = buildAttributes(event);
    final String text =
        String.format(
            "Case %s reached %s via %s",
            caseIdStr,
            event.eventType(),
            event.commandType() != null ? event.commandType() : "unknown");

    final MemoryInput input =
        new MemoryInput(
            caseIdStr, DOMAIN, event.tenancyId(), caseIdStr, text, attrs, null, null, null, null);

    emitter.emit(input);
  }

  private Map<String, String> buildAttributes(final CaseLifecycleEvent event) {
    final Map<String, String> attrs = new java.util.LinkedHashMap<>();
    attrs.put("eventType", event.eventType());
    if (event.commandType() != null) {
      attrs.put("commandType", event.commandType());
    }
    if (event.caseStatus() != null) {
      attrs.put("caseStatus", event.caseStatus());
    }
    if (event.actorId() != null) {
      attrs.put("actorId", event.actorId());
    }
    return Map.copyOf(attrs);
  }
}
