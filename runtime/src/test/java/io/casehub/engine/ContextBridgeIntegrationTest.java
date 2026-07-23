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

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.casehub.api.context.MapBridge;
import io.casehub.api.engine.CaseHub;
import io.casehub.api.engine.CaseHubRuntime;
import io.casehub.api.model.Binding;
import io.casehub.api.model.CaseDefinition;
import io.casehub.api.model.ContextChangeTrigger;
import io.casehub.api.model.Goal;
import io.casehub.api.model.GoalExpression;
import io.casehub.api.model.GoalKind;
import io.casehub.api.model.event.CaseHubEventType;
import io.casehub.engine.common.internal.context.BridgeResolver;
import io.casehub.engine.common.spi.EventLogRepository;
import io.casehub.engine.common.spi.cache.CaseInstanceCache;
import io.casehub.platform.api.identity.TenancyConstants;
import io.casehub.worker.api.Capability;
import io.casehub.worker.api.Worker;
import io.casehub.worker.api.WorkerResult;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/**
 * Integration tests for the ContextBridge protocol — verifies typed context translation across the
 * full Case → Worker pipeline. Covers MapBridge identity (untyped), JacksonPojoBridge (typed POJO),
 * EventLog metadata recording, backward compatibility, and mixed bridge coexistence. Refs
 * casehubio/engine#203.
 */
@QuarkusTest
class ContextBridgeIntegrationTest {

  @Inject CaseHubRuntime runtime;
  @Inject CaseInstanceCache caseInstanceCache;
  @Inject EventLogRepository eventLogRepository;
  @Inject BridgeResolver bridgeResolver;

  private static final ObjectMapper MAPPER = new ObjectMapper();

  @Inject UntypedWorkerBean untypedWorkerBean;
  @Inject TypedPojoWorkerBean typedPojoWorkerBean;
  @Inject MixedBridgeBean mixedBridgeBean;

  // ── POJO types for typed bridges ──────────────────────────────────────────

  public record TransactionInput(String txnId, double amount) {}

  public record AssessmentInput(String entityId, String category) {}

  // ── Pattern 1: MapBridge identity ─────────────────────────────────────────

  @Test
  void untypedWorkerReceivesMapViaIdentityBridge() {
    UUID caseId =
        untypedWorkerBean.startCase(Map.of("documentId", "DOC-1", "status", "processing"));

    await()
        .atMost(15, SECONDS)
        .untilAsserted(
            () -> {
              var instance = caseInstanceCache.get(caseId);
              assertThat(instance.getCaseContext().get("processedResult"))
                  .isEqualTo("processed-DOC-1");
            });
  }

  // ── Pattern 2: Typed POJO via JacksonPojoBridge ───────────────────────────

  @Test
  void typedWorkerReceivesPojoViaJacksonBridge() {
    UUID caseId = typedPojoWorkerBean.startCase(Map.of("txnId", "TXN-42", "amount", 5000.0));

    await()
        .atMost(15, SECONDS)
        .untilAsserted(
            () -> {
              var instance = caseInstanceCache.get(caseId);
              assertThat(instance.getCaseContext().get("risk")).isEqualTo("HIGH");
            });

    assertThat(TypedPojoWorkerBean.capturedInputType.get())
        .as("Worker must receive TransactionInput, not Map")
        .isEqualTo(TransactionInput.class);
  }

  // ── EventLog metadata — typed worker ──────────────────────────────────────

  @Test
  void eventLogRecordsBridgeTypeForTypedWorker() {
    UUID caseId = typedPojoWorkerBean.startCase(Map.of("txnId", "TXN-META", "amount", 100.0));

    await()
        .atMost(15, SECONDS)
        .untilAsserted(
            () -> {
              var instance = caseInstanceCache.get(caseId);
              assertThat(instance.getCaseContext().get("risk")).isNotNull();
            });

    var logs =
        eventLogRepository.findByCaseAndTypes(
            caseId, List.of(CaseHubEventType.WORKER_SCHEDULED), TenancyConstants.DEFAULT_TENANT_ID);

    assertThat(logs).isNotEmpty();
    var scheduledLog = logs.get(0);
    assertThat(scheduledLog.getMetadata().path("contextBridgeType").asText())
        .isEqualTo(TransactionInput.class.getName());
  }

  // ── EventLog metadata — untyped worker records java.util.Map ──────────────

  @Test
  void eventLogRecordsMapBridgeTypeForUntypedWorker() {
    UUID caseId =
        untypedWorkerBean.startCase(Map.of("documentId", "DOC-META", "status", "processing"));

    await()
        .atMost(15, SECONDS)
        .untilAsserted(
            () -> {
              var instance = caseInstanceCache.get(caseId);
              assertThat(instance.getCaseContext().get("processedResult")).isNotNull();
            });

    var logs =
        eventLogRepository.findByCaseAndTypes(
            caseId, List.of(CaseHubEventType.WORKER_SCHEDULED), TenancyConstants.DEFAULT_TENANT_ID);

    assertThat(logs).isNotEmpty();
    var scheduledLog = logs.get(0);
    assertThat(scheduledLog.getMetadata().path("contextBridgeType").asText())
        .isEqualTo("java.util.Map");
  }

  // ── Backward compatibility: pre-bridge EventLog (no contextBridgeType) ────

  @Test
  void preBridgeEventLogDeserializesToMap() {
    io.casehub.api.context.ContextBridge<?> bridge = bridgeResolver.resolveByTypeName(null);
    assertThat(bridge).isInstanceOf(MapBridge.class);

    var payload = MAPPER.valueToTree(Map.of("key", "value", "count", 42));
    Object result = bridgeResolver.deserialise(bridge, payload);

    assertThat(result).isInstanceOf(Map.class);
    @SuppressWarnings("unchecked")
    var map = (Map<String, Object>) result;
    assertThat(map).containsEntry("key", "value").containsEntry("count", 42);
  }

  @Test
  void emptyStringBridgeTypeNameFallsBackToMapBridge() {
    io.casehub.api.context.ContextBridge<?> bridge = bridgeResolver.resolveByTypeName("");
    assertThat(bridge).isInstanceOf(MapBridge.class);
  }

  // ── Mixed bridge types in same case ───────────────────────────────────────

  @Test
  void mixedBridgeTypesCoexistInSameCase() {
    UUID caseId =
        mixedBridgeBean.startCase(
            Map.of("entityId", "ENT-1", "category", "compliance", "documentId", "DOC-MIX"));

    await()
        .atMost(15, SECONDS)
        .untilAsserted(
            () -> {
              var instance = caseInstanceCache.get(caseId);
              assertThat(instance.getCaseContext().get("assessed"))
                  .as("Typed worker output")
                  .isEqualTo(true);
              assertThat(instance.getCaseContext().get("processed"))
                  .as("Untyped worker output")
                  .isEqualTo(true);
            });

    var logs =
        eventLogRepository.findByCaseAndTypes(
            caseId, List.of(CaseHubEventType.WORKER_SCHEDULED), TenancyConstants.DEFAULT_TENANT_ID);

    assertThat(logs).hasSizeGreaterThanOrEqualTo(2);

    var bridgeTypes =
        logs.stream().map(l -> l.getMetadata().path("contextBridgeType").asText()).toList();

    assertThat(bridgeTypes)
        .as("Both bridge types must be recorded in EventLog metadata")
        .contains(AssessmentInput.class.getName(), "java.util.Map");
  }

  // ── CaseHub beans ─────────────────────────────────────────────────────────

  @ApplicationScoped
  public static class UntypedWorkerBean extends CaseHub {
    @Override
    public CaseDefinition getDefinition() {
      Capability cap =
          Capability.builder()
              .name("processDoc")
              .inputSchema("{ documentId, status }")
              .outputSchema(".")
              .build();

      Goal done =
          Goal.builder()
              .name("docProcessed")
              .kind(GoalKind.SUCCESS)
              .condition(".processedResult != null")
              .build();

      return CaseDefinition.builder()
          .namespace("test")
          .name("UntypedBridgeTest")
          .version("1.0.0")
          .capabilities(cap)
          .workers(
              Worker.builder()
                  .name("doc-processor")
                  .capabilityName("processDoc")
                  .function(
                      input ->
                          WorkerResult.of(
                              Map.of("processedResult", "processed-" + input.get("documentId"))))
                  .build())
          .bindings(
              Binding.builder()
                  .name("process-trigger")
                  .capability(cap)
                  .on(new ContextChangeTrigger(".status == \"processing\""))
                  .build())
          .goals(done)
          .completion(GoalExpression.allOf(done))
          .build();
    }
  }

  @ApplicationScoped
  public static class TypedPojoWorkerBean extends CaseHub {
    static final AtomicReference<Class<?>> capturedInputType = new AtomicReference<>();

    @Override
    public CaseDefinition getDefinition() {
      Capability cap =
          Capability.builder()
              .name("assessTxn")
              .inputSchema("{ txnId, amount }")
              .outputSchema(".")
              .build();

      Goal done =
          Goal.builder().name("assessed").kind(GoalKind.SUCCESS).condition(".risk != null").build();

      return CaseDefinition.builder()
          .namespace("test")
          .name("TypedBridgeTest")
          .version("1.0.0")
          .capabilities(cap)
          .workers(
              Worker.builder()
                  .name("txn-assessor")
                  .capabilityName("assessTxn")
                  .<TransactionInput>fn()
                  .apply(
                      input -> {
                        capturedInputType.set(input.getClass());
                        return WorkerResult.of(
                            Map.of("risk", input.amount() > 1000 ? "HIGH" : "LOW"));
                      })
                  .build())
          .bindings(
              Binding.builder()
                  .name("assess-trigger")
                  .capability(cap)
                  .on(new ContextChangeTrigger(".txnId != null"))
                  .build())
          .goals(done)
          .completion(GoalExpression.allOf(done))
          .build();
    }
  }

  @ApplicationScoped
  public static class MixedBridgeBean extends CaseHub {
    @Override
    public CaseDefinition getDefinition() {
      Capability assessCap =
          Capability.builder()
              .name("assessEntity")
              .inputSchema("{ entityId, category }")
              .outputSchema(".")
              .build();

      Capability processCap =
          Capability.builder()
              .name("processItem")
              .inputSchema("{ documentId }")
              .outputSchema(".")
              .build();

      Goal done =
          Goal.builder()
              .name("allDone")
              .kind(GoalKind.SUCCESS)
              .condition(".assessed == true and .processed == true")
              .build();

      return CaseDefinition.builder()
          .namespace("test")
          .name("MixedBridgeTest")
          .version("1.0.0")
          .capabilities(assessCap, processCap)
          .workers(
              Worker.builder()
                  .name("entity-assessor")
                  .capabilityName("assessEntity")
                  .<AssessmentInput>fn()
                  .apply(
                      input ->
                          WorkerResult.of(
                              Map.of("assessed", true, "assessedEntity", input.entityId())))
                  .build(),
              Worker.builder()
                  .name("item-processor")
                  .capabilityName("processItem")
                  .function(input -> WorkerResult.of(Map.of("processed", true)))
                  .build())
          .bindings(
              Binding.builder()
                  .name("assess-trigger")
                  .capability(assessCap)
                  .on(new ContextChangeTrigger(".entityId != null"))
                  .build(),
              Binding.builder()
                  .name("process-trigger")
                  .capability(processCap)
                  .on(new ContextChangeTrigger(".documentId != null"))
                  .build())
          .goals(done)
          .completion(GoalExpression.allOf(done))
          .build();
    }
  }
}
