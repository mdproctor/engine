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
package io.casehub.engine;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.casehub.api.engine.CaseHub;
import io.casehub.api.model.CaseDefinition;
import io.casehub.api.model.event.CaseHubEventType;
import io.casehub.api.model.event.EventStreamType;
import io.casehub.engine.common.internal.history.EventLog;
import io.casehub.engine.common.internal.model.CaseInstance;
import io.casehub.engine.common.spi.EventLogRepository;
import io.casehub.engine.common.spi.cache.CaseInstanceCache;
import io.casehub.engine.common.spi.recovery.WorkerExecutionRecoveryService;
import io.casehub.platform.api.identity.TenancyConstants;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

@QuarkusTest
class ReplayProjectionContractTest {

  private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
  private static final Duration TIMEOUT = Duration.ofSeconds(10);

  @Inject ReplayProjectionCaseHubBean bean;

  @Inject EventLogRepository eventLogRepository;

  @Inject WorkerExecutionRecoveryService recoveryService;

  @Inject CaseInstanceCache caseInstanceCache;

  @Test
  void replayAppliesRecordedContextChangesWithoutReplacingContextWithWorkerPayload()
      throws Exception {
    UUID caseId = bean.startCase(Map.of("documentId", "doc-replay", "status", "submitted"));

    EventLog workerCompleted = new EventLog();
    workerCompleted.setCaseId(caseId);
    workerCompleted.setWorkerId("projection-worker");
    workerCompleted.setEventType(CaseHubEventType.WORKER_EXECUTION_COMPLETED);
    workerCompleted.setStreamType(EventStreamType.CASE);
    workerCompleted.setTimestamp(Instant.now());
    workerCompleted.setPayload(OBJECT_MAPPER.valueToTree(Map.of("workerReturnValue", "ignored")));

    ObjectNode metadata = OBJECT_MAPPER.createObjectNode();
    metadata.put("inputDataHash", "projection-input");
    metadata.set(
        "contextChanges",
        OBJECT_MAPPER.readTree(
            """
            [
              {"op":"replace","path":"/status","value":"processed"},
              {"op":"add","path":"/approved","value":true}
            ]
            """));
    workerCompleted.setMetadata(metadata);
    eventLogRepository.append(workerCompleted, TenancyConstants.DEFAULT_TENANT_ID);

    caseInstanceCache.clear();

    CaseInstance restored = recoveryService.loadOrRestoreCaseInstance(caseId);

    assertThat(restored.getCaseContext().get("documentId")).isEqualTo("doc-replay");
    assertThat(restored.getCaseContext().get("status")).isEqualTo("processed");
    assertThat(restored.getCaseContext().get("approved")).isEqualTo(true);
    assertThat(restored.getCaseContext().get("workerReturnValue")).isNull();
  }

  @ApplicationScoped
  public static class ReplayProjectionCaseHubBean extends CaseHub {

    @Override
    public CaseDefinition getDefinition() {
      return CaseDefinition.builder()
          .namespace("test-replay-projection")
          .name("Replay Projection Contract Test")
          .version("1.0.0")
          .build();
    }
  }
}
