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
import io.casehub.neocortex.memory.CaseMemoryStore;
import io.casehub.neocortex.memory.MemoryDomain;
import io.casehub.neocortex.memory.MemoryInput;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.ObservesAsync;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import java.util.Map;
import java.util.Set;
import org.jboss.logging.Logger;

/**
 * CDI observer that automatically stores structured memories for terminal case lifecycle events.
 *
 * <p>Observes {@link CaseLifecycleEvent} fired by the engine and writes a memory entry for events
 * listed in {@link #CAPTURED_EVENTS} (currently: {@code CaseCompleted}, {@code CaseCancelled},
 * {@code CaseFailed}). These are terminal states — the fact that a case reached them is a
 * system-observed fact worth persisting.
 *
 * <p>Uses {@link Instance} injection so that the observer is a no-op when no {@link
 * CaseMemoryStore} implementation is present on the classpath. The {@code NoOpCaseMemoryStore}
 * (from casehub-platform) is a {@code @DefaultBean} that absorbs calls when no real store is
 * installed. NOT {@code @Transactional} — the default no-op store needs no transaction; JPA-backed
 * real stores must manage their own transaction internally (the observer fires on a managed
 * executor thread where starting a transaction per non-terminal event would exhaust the connection
 * pool).
 *
 * <p>Memory fields:
 *
 * <ul>
 *   <li>{@code entityId} = caseId (case-scoped identity)
 *   <li>{@code tenantId} = caseId (system-generated — not user-tenant-scoped; consumers providing a
 *       real store must map this to their tenant model or provide their own observer)
 *   <li>{@code domain} = {@code "case-lifecycle"}
 *   <li>{@code text} = human-readable event summary
 *   <li>{@code attributes} = eventType, commandType, caseStatus (when present)
 * </ul>
 */
@ApplicationScoped
public class CaseMemoryObserver {

  private static final Logger LOG = Logger.getLogger(CaseMemoryObserver.class);

  private static final MemoryDomain DOMAIN = new MemoryDomain("case-lifecycle");

  private static final Set<String> CAPTURED_EVENTS =
      Set.of("CaseCompleted", "CaseCancelled", "CaseFailed");

  private final Instance<CaseMemoryStore> memoryStore;

  @Inject
  public CaseMemoryObserver(final Instance<CaseMemoryStore> memoryStore) {
    this.memoryStore = memoryStore;
  }

  public void onCaseLifecycleEvent(@ObservesAsync final CaseLifecycleEvent event) {
    if (!CAPTURED_EVENTS.contains(event.eventType())) {
      return;
    }
    if (!memoryStore.isResolvable()) {
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

    final MemoryInput input = new MemoryInput(caseIdStr, DOMAIN, caseIdStr, caseIdStr, text, attrs);

    final CaseMemoryStore store = memoryStore.get();
    try {
      store.store(input);
    } catch (final Exception e) {
      LOG.warnf(
          "CaseMemoryObserver: failed to store memory for caseId=%s event=%s: %s",
          caseIdStr, event.eventType(), e.getMessage());
    } finally {
      memoryStore.destroy(store);
    }
  }

  private Map<String, String> buildAttributes(final CaseLifecycleEvent event) {
    final Map<String, String> attrs = new java.util.LinkedHashMap<>();
    attrs.put("eventType", event.eventType());
    if (event.commandType() != null) attrs.put("commandType", event.commandType());
    if (event.caseStatus() != null) attrs.put("caseStatus", event.caseStatus());
    if (event.actorId() != null) attrs.put("actorId", event.actorId());
    return Map.copyOf(attrs);
  }
}
