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
import static org.awaitility.Awaitility.await;

import io.casehub.api.engine.CaseHub;
import io.casehub.api.model.Binding;
import io.casehub.api.model.Capability;
import io.casehub.api.model.CaseChannel;
import io.casehub.api.model.CaseDefinition;
import io.casehub.api.model.CaseStatus;
import io.casehub.api.model.ContextChangeTrigger;
import io.casehub.api.model.ProvisionContext;
import io.casehub.api.model.WorkRequest;
import io.casehub.api.model.WorkResult;
import io.casehub.api.model.Worker;
import io.casehub.api.model.WorkerContext;
import io.casehub.api.model.WorkerExecutionContext;
import io.casehub.api.spi.CaseChannelProvider;
import io.casehub.api.spi.ProvisioningException;
import io.casehub.api.spi.ReactiveWorkerProvisioner;
import io.casehub.api.spi.WorkerContextProvider;
import io.casehub.api.spi.WorkerProvisioner;
import io.casehub.api.spi.WorkerStatusListener;
import io.casehub.engine.spi.cache.CaseInstanceCache;
import io.casehub.qhorus.api.message.MessageType;
import io.quarkus.test.junit.QuarkusTest;
import io.smallrye.mutiny.Uni;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Alternative;
import jakarta.inject.Inject;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Verifies that WorkerStatusListener, WorkerContextProvider, CaseChannelProvider, and
 * WorkerProvisioner are called at the correct lifecycle points by the engine. Refs
 * casehubio/engine#152, casehubio/engine#191, casehubio/engine#220.
 *
 * <p>SPI displacement is exercised via {@code @Alternative @Priority(1)} recording beans defined as
 * static inner classes below. These override the engine's {@code @DefaultBean} no-op defaults
 * without requiring {@code quarkus.arc.selected-alternatives} configuration.
 */
@QuarkusTest
class SpiWiringIntegrationTest {

  @Inject SimpleCaseHubBean simpleCaseHubBean;
  @Inject CaseFaultedStateTest.AlwaysFailingCaseHubBean alwaysFailingBean;
  @Inject ProvisionerTriggerCaseHubBean provisionerTriggerBean;
  @Inject RecordingContextCaseHubBean recordingContextBean;
  @Inject CaseInstanceCache caseInstanceCache;
  @Inject RecordingWorkerStatusListener statusListener;
  @Inject RecordingCaseChannelProvider channelProvider;

  @BeforeEach
  void reset() {
    RecordingWorkerStatusListener.reset();
    RecordingWorkerContextProvider.reset();
    RecordingCaseChannelProvider.reset();
    RecordingWorkerProvisioner.reset();
  }

  // ------------------------------------------------------------------ //
  // WorkerStatusListener                                                 //
  // ------------------------------------------------------------------ //

  @Test
  void onWorkerStartedCalledWhenWorkerBegins() {
    UUID caseId =
        simpleCaseHubBean
            .startCase(Map.of("documentId", "doc-1", "status", "processing"))
            .toCompletableFuture()
            .join();

    await()
        .atMost(15, TimeUnit.SECONDS)
        .untilAsserted(
            () ->
                assertThat(RecordingWorkerStatusListener.startedWorkerIds)
                    .as("onWorkerStarted must be called when a worker begins execution")
                    .isNotEmpty());
  }

  @Test
  void onWorkerCompletedCalledAfterSuccessfulExecution() {
    UUID caseId =
        simpleCaseHubBean
            .startCase(Map.of("documentId", "doc-2", "status", "processing"))
            .toCompletableFuture()
            .join();

    await()
        .atMost(15, TimeUnit.SECONDS)
        .untilAsserted(
            () ->
                assertThat(RecordingWorkerStatusListener.completedWorkerIds)
                    .as("onWorkerCompleted must be called after worker finishes successfully")
                    .isNotEmpty());

    assertThat(RecordingWorkerStatusListener.lastCompletedResult)
        .as("completed result must carry the output and workerId")
        .isNotNull();
    assertThat(RecordingWorkerStatusListener.lastCompletedResult.status().name())
        .isEqualTo("COMPLETED");
  }

  @Test
  void onWorkerStalledCalledWhenRetriesExhausted() {
    CaseFaultedStateTest.AlwaysFailingCaseHubBean.runCount.set(0);
    UUID caseId =
        alwaysFailingBean.startCase(Map.of("status", "processing")).toCompletableFuture().join();

    await()
        .atMost(30, TimeUnit.SECONDS)
        .untilAsserted(
            () ->
                assertThat(RecordingWorkerStatusListener.stalledWorkerIds)
                    .as("onWorkerStalled must be called when all retries are exhausted")
                    .isNotEmpty());
  }

  // ------------------------------------------------------------------ //
  // CaseChannelProvider                                                  //
  // ------------------------------------------------------------------ //

  @Test
  void openChannelCalledWhenCaseStarts() {
    UUID caseId =
        simpleCaseHubBean
            .startCase(Map.of("documentId", "doc-5", "status", "processing"))
            .toCompletableFuture()
            .join();

    await()
        .atMost(10, TimeUnit.SECONDS)
        .untilAsserted(
            () ->
                assertThat(RecordingCaseChannelProvider.openedCaseIds)
                    .as("openChannel must be called when a case starts")
                    .contains(caseId));
  }

  @Test
  void closeChannelCalledWhenCaseReachesTerminalState() {
    UUID caseId =
        simpleCaseHubBean
            .startCase(Map.of("documentId", "doc-6", "status", "processing"))
            .toCompletableFuture()
            .join();

    await()
        .atMost(15, TimeUnit.SECONDS)
        .untilAsserted(
            () ->
                assertThat(caseInstanceCache.get(caseId).getState())
                    .isEqualTo(CaseStatus.COMPLETED));

    assertThat(RecordingCaseChannelProvider.closedCaseIds)
        .as("closeChannel must be called when a case reaches a terminal state")
        .contains(caseId);
  }

  @Test
  void commandDispatchedToChannelWhenWorkerScheduled() {
    simpleCaseHubBean
        .startCase(Map.of("documentId", "doc-cmd-1", "status", "processing"))
        .toCompletableFuture()
        .join();

    await()
        .atMost(15, TimeUnit.SECONDS)
        .untilAsserted(
            () ->
                assertThat(RecordingCaseChannelProvider.postedContents)
                    .as("postToChannel must be called with a COMMAND when a worker is scheduled")
                    .anyMatch(c -> c.contains("\"type\":\"COMMAND\"")));

    assertThat(RecordingCaseChannelProvider.postedContents)
        .as("COMMAND must include the capability name")
        .anyMatch(c -> c.contains("processDocument"));
    assertThat(RecordingCaseChannelProvider.postedFroms)
        .as("COMMAND sender must identify casehub-engine as the orchestrator")
        .anyMatch(f -> f.startsWith("casehub-engine:orchestrator"));
    assertThat(RecordingCaseChannelProvider.postedTypes).contains(MessageType.COMMAND);
  }

  // ------------------------------------------------------------------ //
  // WorkerContextProvider + WorkerExecutionContext                        //
  // ------------------------------------------------------------------ //

  @Test
  void workerExecutionContext_channelsAccessibleDuringExecution() {
    RecordingExecutionContextWorker.capturedChannels.clear();
    recordingContextBean
        .startCase(Map.of("documentId", "doc-exec-ctx-1", "status", "processing"))
        .toCompletableFuture()
        .join();

    await()
        .atMost(15, TimeUnit.SECONDS)
        .untilAsserted(
            () ->
                assertThat(RecordingExecutionContextWorker.capturedChannels)
                    .as(
                        "WorkerExecutionContext.current() must be non-null with channels during"
                            + " worker function execution")
                    .isNotEmpty());
  }

  @Test
  void buildContextCalledBeforeWorkerExecution() {
    simpleCaseHubBean
        .startCase(Map.of("documentId", "doc-ctx-1", "status", "processing"))
        .toCompletableFuture()
        .join();

    await()
        .atMost(15, TimeUnit.SECONDS)
        .untilAsserted(
            () ->
                assertThat(RecordingWorkerContextProvider.buildContextCallCount.get())
                    .as("buildContext must be called at least once before worker execution")
                    .isPositive());
  }

  @Test
  void buildContextReceivesCorrectCapabilityName() {
    simpleCaseHubBean
        .startCase(Map.of("documentId", "doc-ctx-2", "status", "processing"))
        .toCompletableFuture()
        .join();

    await()
        .atMost(15, TimeUnit.SECONDS)
        .untilAsserted(
            () ->
                assertThat(RecordingWorkerContextProvider.seenCapabilities)
                    .as("buildContext must receive the capability name from the binding")
                    .contains("processDocument"));
  }

  // ------------------------------------------------------------------ //
  // WorkerProvisioner                                                    //
  // ------------------------------------------------------------------ //

  @Test
  void workerProvisionerCalledWhenNoCandidateWorkerAvailable() {
    provisionerTriggerBean
        .startCase(Map.of("taskId", "task-prov-1", "status", "pending"))
        .toCompletableFuture()
        .join();

    await()
        .atMost(10, TimeUnit.SECONDS)
        .untilAsserted(
            () ->
                assertThat(RecordingWorkerProvisioner.lastProvisionContext)
                    .as(
                        "provision() must be called when no pre-defined worker matches the capability")
                    .isNotNull());

    assertThat(RecordingWorkerProvisioner.lastProvisionContext.caseId()).isNotNull();
    assertThat(RecordingWorkerProvisioner.lastProvisionContext.taskType())
        .isEqualTo("external-task");
  }

  @Test
  void provisioningExceptionCaughtGracefully() {
    RecordingWorkerProvisioner.shouldThrow.set(true);

    UUID caseId =
        provisionerTriggerBean
            .startCase(Map.of("taskId", "task-prov-2", "status", "pending"))
            .toCompletableFuture()
            .join();

    await()
        .atMost(5, TimeUnit.SECONDS)
        .until(() -> RecordingWorkerProvisioner.lastProvisionContext != null);

    assertThat(caseInstanceCache.get(caseId).getState())
        .as("case must not fault when WorkerProvisioner throws ProvisioningException")
        .isNotEqualTo(CaseStatus.FAULTED);
  }

  // ------------------------------------------------------------------ //
  // Recording SPI implementations                                        //
  // ------------------------------------------------------------------ //

  @Alternative
  @Priority(1)
  @ApplicationScoped
  public static class RecordingWorkerStatusListener implements WorkerStatusListener {

    static final List<String> startedWorkerIds = new CopyOnWriteArrayList<>();
    static final List<String> completedWorkerIds = new CopyOnWriteArrayList<>();
    static final List<String> stalledWorkerIds = new CopyOnWriteArrayList<>();
    static volatile WorkResult lastCompletedResult;

    static void reset() {
      startedWorkerIds.clear();
      completedWorkerIds.clear();
      stalledWorkerIds.clear();
      lastCompletedResult = null;
    }

    @Override
    public void onWorkerStarted(String workerId, Map<String, String> sessionMeta) {
      startedWorkerIds.add(workerId);
    }

    @Override
    public void onWorkerCompleted(String workerId, WorkResult result) {
      completedWorkerIds.add(workerId);
      lastCompletedResult = result;
    }

    @Override
    public void onWorkerStalled(String workerId) {
      stalledWorkerIds.add(workerId);
    }
  }

  @Alternative
  @Priority(1)
  @ApplicationScoped
  public static class RecordingWorkerContextProvider implements WorkerContextProvider {

    static final AtomicInteger buildContextCallCount = new AtomicInteger(0);
    static final Set<String> seenCapabilities = ConcurrentHashMap.newKeySet();

    static void reset() {
      buildContextCallCount.set(0);
      seenCapabilities.clear();
    }

    @Override
    public WorkerContext buildContext(String workerId, UUID caseId, WorkRequest task) {
      buildContextCallCount.incrementAndGet();
      seenCapabilities.add(task.capability());
      return new WorkerContext(task.capability(), caseId, null, List.of(), null, Map.of());
    }
  }

  @Alternative
  @Priority(1)
  @ApplicationScoped
  public static class RecordingCaseChannelProvider implements CaseChannelProvider {

    static final Set<UUID> openedCaseIds = ConcurrentHashMap.newKeySet();
    static final Set<UUID> closedCaseIds = ConcurrentHashMap.newKeySet();
    static final List<String> postedContents = new CopyOnWriteArrayList<>();
    static final List<String> postedFroms = new CopyOnWriteArrayList<>();
    static final List<MessageType> postedTypes = new CopyOnWriteArrayList<>();
    private final Map<UUID, List<CaseChannel>> openChannels = new ConcurrentHashMap<>();

    static void reset() {
      openedCaseIds.clear();
      closedCaseIds.clear();
      postedContents.clear();
      postedFroms.clear();
      postedTypes.clear();
    }

    @Override
    public CaseChannel openChannel(UUID caseId, String purpose) {
      openedCaseIds.add(caseId);
      CaseChannel channel =
          new CaseChannel(caseId + "/" + purpose, purpose, purpose, "none", Map.of());
      openChannels.computeIfAbsent(caseId, id -> new CopyOnWriteArrayList<>()).add(channel);
      return channel;
    }

    @Override
    public void postToChannel(CaseChannel channel, String from, String content, MessageType type) {
      postedFroms.add(from);
      postedContents.add(content);
      postedTypes.add(type);
    }

    @Override
    public void closeChannel(CaseChannel channel) {
      // channel id is caseId/purpose — extract caseId prefix
      String id = channel.id();
      int slash = id.indexOf('/');
      if (slash > 0) {
        try {
          closedCaseIds.add(UUID.fromString(id.substring(0, slash)));
        } catch (IllegalArgumentException ignored) {
        }
      }
    }

    @Override
    public List<CaseChannel> listChannels(UUID caseId) {
      return openChannels.getOrDefault(caseId, List.of());
    }
  }

  @Alternative
  @Priority(1)
  @ApplicationScoped
  public static class RecordingWorkerProvisioner implements WorkerProvisioner {

    static volatile ProvisionContext lastProvisionContext;
    static final java.util.concurrent.atomic.AtomicBoolean shouldThrow =
        new java.util.concurrent.atomic.AtomicBoolean(false);

    static void reset() {
      lastProvisionContext = null;
      shouldThrow.set(false);
    }

    @Override
    public Worker provision(Set<String> capabilities, ProvisionContext context) {
      lastProvisionContext = context;
      if (shouldThrow.get()) {
        throw new ProvisioningException("RecordingWorkerProvisioner: intentional failure for test");
      }
      Capability cap =
          Capability.builder().name("external-task").inputSchema("{}").outputSchema("{}").build();
      return Worker.builder()
          .name("provisioned-worker-" + UUID.randomUUID())
          .capabilities(cap)
          .function(
              (java.util.function.Function<Map<String, Object>, Map<String, Object>>) i -> Map.of())
          .build();
    }

    @Override
    public void terminate(String workerId) {}

    @Override
    public Set<String> getCapabilities() {
      return Set.of("external-task");
    }
  }

  @Alternative
  @Priority(1)
  @ApplicationScoped
  public static class RecordingReactiveWorkerProvisioner implements ReactiveWorkerProvisioner {

    @Override
    public Uni<Worker> provision(Set<String> capabilities, ProvisionContext context) {
      RecordingWorkerProvisioner.lastProvisionContext = context;
      if (RecordingWorkerProvisioner.shouldThrow.get()) {
        return Uni.createFrom()
            .failure(
                new ProvisioningException(
                    "RecordingReactiveWorkerProvisioner: intentional failure for test"));
      }
      Capability cap =
          Capability.builder().name("external-task").inputSchema("{}").outputSchema("{}").build();
      Worker worker =
          Worker.builder()
              .name("provisioned-worker-" + UUID.randomUUID())
              .capabilities(cap)
              .function(
                  (java.util.function.Function<Map<String, Object>, Map<String, Object>>)
                      i -> Map.of())
              .build();
      return Uni.createFrom().item(worker);
    }

    @Override
    public Uni<Void> terminate(String workerId) {
      return Uni.createFrom().voidItem();
    }

    @Override
    public Uni<Set<String>> getCapabilities() {
      return Uni.createFrom().item(Set.of("external-task"));
    }
  }

  @ApplicationScoped
  public static class ProvisionerTriggerCaseHubBean extends CaseHub {

    @Override
    public CaseDefinition getDefinition() {
      Capability capability =
          Capability.builder()
              .name("external-task")
              .inputSchema("{ taskId: .taskId }")
              .outputSchema("{ result: . }")
              .build();

      return CaseDefinition.builder()
          .namespace("test")
          .name("Provisioner Trigger Test Case")
          .version("1.0.0")
          .capabilities(capability)
          .bindings(
              Binding.builder()
                  .name("trigger-on-pending")
                  .capability(capability)
                  .on(new ContextChangeTrigger(".status == \"pending\""))
                  .build())
          .build();
    }
  }

  // ------------------------------------------------------------------ //
  // WorkerExecutionContext wiring                                         //
  // ------------------------------------------------------------------ //

  /** Worker that captures the channels visible via WorkerExecutionContext during execution. */
  public static class RecordingExecutionContextWorker {
    static final List<List<CaseChannel>> capturedChannels = new CopyOnWriteArrayList<>();
  }

  @ApplicationScoped
  public static class RecordingContextCaseHubBean extends CaseHub {

    @Override
    public CaseDefinition getDefinition() {
      Capability capability =
          Capability.builder()
              .name("recordContext")
              .inputSchema("{ documentId: .documentId }")
              .outputSchema("{ recorded: true }")
              .build();

      Worker worker =
          Worker.builder()
              .name("execution-context-recorder")
              .capabilities(capability)
              .function(
                  (java.util.function.Function<Map<String, Object>, Map<String, Object>>)
                      input -> {
                        WorkerContext ctx = WorkerExecutionContext.current();
                        if (ctx != null) {
                          RecordingExecutionContextWorker.capturedChannels.add(ctx.channels());
                        }
                        return Map.of("recorded", true);
                      })
              .build();

      return CaseDefinition.builder()
          .namespace("test")
          .name("Execution Context Recording Case")
          .version("1.0.0")
          .capabilities(capability)
          .workers(worker)
          .bindings(
              Binding.builder()
                  .name("trigger-on-processing")
                  .capability(capability)
                  .on(new ContextChangeTrigger(".status == \"processing\""))
                  .build())
          .build();
    }
  }
}
