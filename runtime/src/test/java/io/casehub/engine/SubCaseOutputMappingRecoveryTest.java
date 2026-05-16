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
import io.casehub.api.model.CaseStatus;
import io.casehub.api.model.event.CaseHubEventType;
import io.casehub.api.model.event.EventStreamType;
import io.casehub.engine.internal.history.EventLog;
import io.casehub.engine.internal.model.CaseInstance;
import io.casehub.engine.internal.model.CaseMetaModel;
import io.casehub.engine.spi.CaseInstanceRepository;
import io.casehub.engine.spi.CaseMetaModelRepository;
import io.casehub.engine.spi.EventLogRepository;
import io.casehub.engine.spi.cache.CaseInstanceCache;
import io.casehub.engine.spi.recovery.WorkerExecutionRecoveryService;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.vertx.VertxContextSupport;
import io.smallrye.mutiny.Uni;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Tests for SubCase outputMapping recovery after JVM restart.
 *
 * <p>CRITICAL BUG: SubCase outputMapping data is lost after restart because:
 *
 * <ul>
 *   <li>SUBCASE_COMPLETED events are written WITHOUT payload containing applied data
 *   <li>DefaultWorkerExecutionRecoveryService.rebuildStateContext() does NOT process
 *       SUBCASE_COMPLETED events
 * </ul>
 *
 * <p>This test demonstrates the bug by simulating the exact sequence that happens in production.
 *
 * <p>Related issues: #13, #10, #12
 */
@QuarkusTest
public class SubCaseOutputMappingRecoveryTest {

  private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

  @Inject CaseInstanceRepository instanceRepository;
  @Inject CaseMetaModelRepository metaModelRepository;
  @Inject EventLogRepository eventLogRepository;
  @Inject CaseInstanceCache caseInstanceCache;
  @Inject WorkerExecutionRecoveryService recoveryService;

  private CaseMetaModel savedMeta;

  @BeforeEach
  void setUp() {
    String unique = UUID.randomUUID().toString().substring(0, 8);
    CaseMetaModel meta = new CaseMetaModel();
    meta.setName("recovery-test-" + unique);
    meta.setNamespace("test-ns");
    meta.setVersion("1.0");
    savedMeta = run(() -> metaModelRepository.save(meta));
  }

  /**
   * Verifies that SubCase outputMapping data is preserved after restart.
   *
   * <p>Steps:
   *
   * <ol>
   *   <li>Create parent case with initial context: { orderId: "ORDER-1" }
   *   <li>Create child case with result data
   *   <li>Write SUBCASE_STARTED EventLog to parent
   *   <li>Apply outputMapping to parent context IN MEMORY (as SubCaseCompletionListener does)
   *   <li>Write SUBCASE_COMPLETED EventLog WITH payload containing applied data
   *   <li>Clear cache (simulates JVM restart)
   *   <li>Load parent via recoveryService
   *   <li>Verify: outputMapping data is correctly restored from SUBCASE_COMPLETED payload
   * </ol>
   */
  @Test
  void subCaseOutputMapping_preservedAfterRestart() {
    // 1. Create parent case
    final UUID parentId = createParentCase("ORDER-1");

    // 2. Create child case
    final UUID childId = createChildCase(parentId, "approved", Map.of("key", "value", "score", 95));

    // 3. Write SUBCASE_STARTED event
    String outputMapping = "{ approval: .result, data: .processedData }";
    writeSubCaseStartedEvent(parentId, childId, outputMapping);

    // 4. Apply outputMapping to parent IN MEMORY (simulating SubCaseCompletionListener)
    CaseInstance child = caseInstanceCache.get(childId);
    CaseInstance parent = caseInstanceCache.get(parentId);
    Map<String, Object> mappedData = child.getCaseContext().evalObjectTemplate(outputMapping);
    mappedData.forEach((k, v) -> parent.getCaseContext().set(k, v));

    // Verify data was applied in memory
    assertThat(parent.getCaseContext().get("approval")).isEqualTo("approved");
    assertThat(parent.getCaseContext().get("data")).isNotNull();

    // 5. Write SUBCASE_COMPLETED WITH payload (FIXED - now includes applied data)
    writeSubCaseCompletedEvent(parentId, childId, mappedData);

    // 6. Clear cache (simulates JVM restart)
    caseInstanceCache.clear();

    // 7. Restore parent via recovery service
    CaseInstance restored = run(() -> recoveryService.loadOrRestoreCaseInstance(parentId));

    // 8. Verify: outputMapping data is correctly restored!
    assertThat(restored.getCaseContext().get("orderId")).isEqualTo("ORDER-1");

    assertThat(restored.getCaseContext().get("approval"))
        .as("approval should be 'approved' from SubCase outputMapping")
        .isEqualTo("approved");

    assertThat(restored.getCaseContext().get("data"))
        .as("data should be present from SubCase outputMapping")
        .isNotNull();
  }

  /**
   * Demonstrates that regular worker output IS preserved (for comparison).
   *
   * <p>WORKER_EXECUTION_COMPLETED events correctly save output in payload.
   */
  @Test
  void regularWorkerOutput_preservedAfterRestart_forComparison() {
    final UUID caseId = createParentCase("ORDER-2");

    // Write WORKER_EXECUTION_COMPLETED with payload (CORRECT implementation)
    EventLog workerEvent = new EventLog();
    workerEvent.setCaseId(caseId);
    workerEvent.setWorkerId("test-worker");
    workerEvent.setEventType(CaseHubEventType.WORKER_EXECUTION_COMPLETED);
    workerEvent.setStreamType(EventStreamType.CASE);
    workerEvent.setTimestamp(Instant.now());
    // ✓ CORRECT: payload contains output data
    workerEvent.setPayload(
        OBJECT_MAPPER.valueToTree(Map.of("processed", true, "result", "success")));
    run(() -> eventLogRepository.append(workerEvent));

    // Clear cache and restore
    caseInstanceCache.clear();
    CaseInstance restored = run(() -> recoveryService.loadOrRestoreCaseInstance(caseId));

    // ✓ WORKS: worker output is preserved!
    assertThat(restored.getCaseContext().get("orderId")).isEqualTo("ORDER-2");
    assertThat(restored.getCaseContext().get("processed")).isEqualTo(true);
    assertThat(restored.getCaseContext().get("result")).isEqualTo("success");
  }

  // ========== Helper Methods ==========

  private <T> T run(Supplier<Uni<T>> supplier) {
    try {
      return VertxContextSupport.subscribeAndAwait(supplier);
    } catch (RuntimeException e) {
      throw e;
    } catch (Throwable e) {
      throw new RuntimeException(e);
    }
  }

  private UUID createParentCase(String orderId) {
    CaseInstance parent = newInstance(CaseStatus.RUNNING);
    parent.getCaseContext().set("orderId", orderId);
    CaseInstance savedParent = run(() -> instanceRepository.save(parent));

    // Write CASE_STARTED event (needed for recovery)
    EventLog caseStarted = new EventLog();
    caseStarted.setCaseId(savedParent.getUuid());
    caseStarted.setEventType(CaseHubEventType.CASE_STARTED);
    caseStarted.setStreamType(EventStreamType.CASE);
    caseStarted.setTimestamp(Instant.now());
    caseStarted.setPayload(OBJECT_MAPPER.valueToTree(Map.of("orderId", orderId)));
    run(() -> eventLogRepository.append(caseStarted));

    caseInstanceCache.put(savedParent);
    return savedParent.getUuid();
  }

  private UUID createChildCase(UUID parentId, String result, Map<String, Object> processedData) {
    UUID childId = UUID.randomUUID();
    CaseInstance child = newInstance(CaseStatus.COMPLETED);
    child.setUuid(childId);
    child.setParentCaseId(parentId);
    child.getCaseContext().set("result", result);
    child.getCaseContext().set("processedData", processedData);
    run(() -> instanceRepository.save(child));
    caseInstanceCache.put(child);
    return childId;
  }

  private void writeSubCaseStartedEvent(UUID parentId, UUID childId, String outputMapping) {
    EventLog event = new EventLog();
    event.setCaseId(parentId);
    event.setWorkerId(childId.toString());
    event.setEventType(CaseHubEventType.SUBCASE_STARTED);
    event.setStreamType(EventStreamType.CASE);
    event.setTimestamp(Instant.now());
    ObjectNode metadata = OBJECT_MAPPER.createObjectNode();
    metadata.put("childCaseId", childId.toString());
    metadata.put("outputMapping", outputMapping);
    metadata.put("waitForCompletion", true);
    event.setMetadata(metadata);
    run(() -> eventLogRepository.append(event));
  }

  private void writeSubCaseCompletedEvent(
      UUID parentId, UUID childId, Map<String, Object> appliedData) {
    EventLog event = new EventLog();
    event.setCaseId(parentId);
    event.setWorkerId(childId.toString());
    event.setEventType(CaseHubEventType.SUBCASE_COMPLETED);
    event.setStreamType(EventStreamType.CASE);
    event.setTimestamp(Instant.now());
    ObjectNode metadata = OBJECT_MAPPER.createObjectNode();
    metadata.put("childCaseId", childId.toString());
    event.setMetadata(metadata);
    // BUG: NO payload with applied data!
    // This is what current implementation does (incorrectly)
    if (appliedData != null) {
      event.setPayload(OBJECT_MAPPER.valueToTree(appliedData));
    }
    run(() -> eventLogRepository.append(event));
  }

  private CaseInstance newInstance(CaseStatus status) {
    CaseInstance instance = new CaseInstance();
    instance.setUuid(UUID.randomUUID());
    instance.setState(status);
    instance.setCaseMetaModel(savedMeta);
    instance.setCaseContext(new io.casehub.engine.internal.context.CaseContextImpl());
    return instance;
  }
}
