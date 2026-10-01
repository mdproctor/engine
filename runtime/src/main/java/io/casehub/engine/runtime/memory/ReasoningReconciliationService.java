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

import com.fasterxml.jackson.databind.JsonNode;
import io.casehub.api.model.MemoryRetrievalConfig;
import io.casehub.api.model.event.CaseHubEventType;
import io.casehub.engine.common.internal.history.EventLog;
import io.casehub.engine.common.qualifier.CrossTenant;
import io.casehub.engine.common.spi.CrossTenantEventLogRepository;
import io.casehub.neocortex.memory.CaseMemoryStore;
import io.casehub.neocortex.memory.MemoryDomain;
import io.casehub.neocortex.memory.MemoryInput;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import org.jboss.logging.Logger;

@ApplicationScoped
public class ReasoningReconciliationService {

  private static final Logger LOG = Logger.getLogger(ReasoningReconciliationService.class);
  private static final MemoryDomain WORKER_REASONING_DOMAIN = new MemoryDomain("worker-reasoning");

  private final AtomicLong lastProcessedId = new AtomicLong(0);

  @Inject @CrossTenant CrossTenantEventLogRepository eventLogRepository;
  @Inject Instance<CaseMemoryStore> caseMemoryStore;

  @org.eclipse.microprofile.config.inject.ConfigProperty(
      name = "casehub.reasoning.reconciliation.enabled",
      defaultValue = "true")
  boolean enabled;

  @org.eclipse.microprofile.config.inject.ConfigProperty(
      name = "casehub.reasoning.reconciliation.batch-size",
      defaultValue = "100")
  int batchSize;

  @Scheduled(
      identity = "reasoning-reconciliation",
      every = "${casehub.reasoning.reconciliation.interval:300}s",
      delayed = "30s")
  void reconcile() {
    if (!enabled || !caseMemoryStore.isResolvable()) {
      return;
    }

    long watermark = lastProcessedId.get();
    List<EventLog> entries =
        eventLogRepository.findByTypesAfterId(
            List.of(
                CaseHubEventType.WORKER_EXECUTION_COMPLETED,
                CaseHubEventType.WORKER_EXECUTION_FAILED),
            watermark);

    if (entries.isEmpty()) {
      return;
    }

    CaseMemoryStore store = caseMemoryStore.get();
    int reconciledCount = 0;
    long maxId = watermark;

    for (EventLog entry : entries) {
      if (entry.id == null) continue;
      maxId = Math.max(maxId, entry.id);

      String reasoning = extractReasoning(entry);
      if (reasoning == null || reasoning.isBlank()) continue;
      if (reconciledCount >= batchSize) break;

      try {
        MemoryInput input = buildMemoryInput(entry, reasoning);
        store.store(input);
        reconciledCount++;
      } catch (Exception e) {
        LOG.warnf(
            e,
            "Reasoning reconciliation failed for eventLogId=%d caseId=%s — will retry next cycle",
            entry.id,
            entry.getCaseId());
      }
    }

    lastProcessedId.set(maxId);

    if (reconciledCount > 0) {
      LOG.infof(
          "Reasoning reconciliation: %d traces re-stored from EventLog (watermark=%d)",
          reconciledCount, maxId);
    }
  }

  private String extractReasoning(EventLog entry) {
    JsonNode metadata = entry.getMetadata();
    if (metadata == null || !metadata.has("reasoning")) return null;
    JsonNode reasoningNode = metadata.get("reasoning");
    return reasoningNode.isTextual() ? reasoningNode.asText() : null;
  }

  private MemoryInput buildMemoryInput(EventLog entry, String reasoning) {
    HashMap<String, String> attributes = new HashMap<>();
    attributes.put("workerName", entry.getWorkerId());
    if (entry.getMetadata().has("bindingName")) {
      attributes.put("bindingName", entry.getMetadata().get("bindingName").asText());
    }
    String outcomeKind =
        entry.getEventType() == CaseHubEventType.WORKER_EXECUTION_COMPLETED ? "SUCCESS" : "FAILED";
    if (entry.getMetadata().has("disposition")) {
      outcomeKind = entry.getMetadata().get("disposition").asText();
    }
    attributes.put("outcome", outcomeKind);
    attributes.put("reconciled", "true");

    double importance =
        MemoryRetrievalConfig.DEFAULT_REASONING_IMPORTANCE_WEIGHTS.getOrDefault(outcomeKind, 0.3);

    return new MemoryInput(
        "case:" + entry.getCaseId(),
        WORKER_REASONING_DOMAIN,
        entry.tenancyId,
        entry.getCaseId().toString(),
        reasoning,
        Map.copyOf(attributes),
        io.casehub.neocortex.cognitive.Confidence.inferred(importance, Instant.now()),
        null,
        null,
        null);
  }

  long getLastProcessedId() {
    return lastProcessedId.get();
  }

  void resetWatermark() {
    lastProcessedId.set(0);
  }
}
