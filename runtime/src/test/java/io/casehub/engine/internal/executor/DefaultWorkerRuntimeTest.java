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
package io.casehub.engine.internal.executor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.casehub.api.context.CaseContext;
import io.casehub.api.context.PropagationContext;
import io.casehub.api.engine.CaseHubRuntime;
import io.casehub.api.model.CaseDefinition;
import io.casehub.api.model.CaseStatus;
import io.casehub.api.model.WorkerExecutionContext;
import io.casehub.api.model.event.CaseEventLogRecord;
import io.casehub.api.model.event.CaseHubEventType;
import io.casehub.api.model.event.EventStreamType;
import io.casehub.engine.common.internal.model.CaseInstance;
import io.casehub.engine.common.internal.model.CaseMetaModel;
import io.casehub.engine.common.internal.model.CaseTerminatedException;
import io.casehub.engine.common.spi.CaseDefinitionRegistry;
import io.casehub.engine.common.spi.cache.CaseInstanceCache;
import io.casehub.engine.internal.engine.CaseCompletionTracker;
import io.casehub.worker.api.WorkerFunction;
import io.casehub.worker.api.WorkerOutcome;
import io.casehub.worker.api.WorkerResult;
import io.smallrye.mutiny.Uni;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class DefaultWorkerRuntimeTest {

  private static final UUID CASE_ID = UUID.randomUUID();
  private DefaultWorkerRuntime runtime;

  @BeforeEach
  void setUp() {
    runtime = new DefaultWorkerRuntime(CASE_ID, null, null, null, null);
  }

  @AfterEach
  void cleanup() {
    WorkerExecutionContext.clear();
  }

  @Test
  void caseId_returnsConstructorValue() {
    assertEquals(CASE_ID, runtime.caseId());
  }

  @Test
  void execute_syncFunction_returnsResult() {
    WorkerFunction fn =
        new WorkerFunction.Sync(input -> WorkerResult.of(Map.of("result", "hello")));

    WorkerResult result = runtime.execute(fn, Map.of("key", "value"));

    assertInstanceOf(WorkerOutcome.Success.class, result.outcome());
    assertEquals("hello", result.output().get("result"));
  }

  @Test
  void execute_throwingFunction_wrapsInFailed() {
    WorkerFunction fn =
        new WorkerFunction.Sync(
            input -> {
              throw new RuntimeException("boom");
            });

    WorkerResult result = runtime.execute(fn, Map.of());

    assertInstanceOf(WorkerOutcome.Failed.class, result.outcome());
    assertEquals("boom", ((WorkerOutcome.Failed) result.outcome()).reason());
  }

  @Test
  void execute_preservesParentContext() {
    var parentContext =
        new io.casehub.api.model.WorkerContext("parent-task", CASE_ID, null, null, null, null);
    WorkerExecutionContext.set(parentContext);

    WorkerFunction fn =
        new WorkerFunction.Sync(
            input -> {
              var innerCtx = WorkerExecutionContext.current();
              assertNotNull(innerCtx);
              assertEquals(CASE_ID, innerCtx.caseId());
              return WorkerResult.of(Map.of());
            });

    runtime.execute(fn, Map.of());

    assertSame(parentContext, WorkerExecutionContext.current());
  }

  @Test
  void execute_nestedOrchestration_stackSemantics() {
    List<String> order = new ArrayList<>();
    WorkerFunction inner =
        new WorkerFunction.Sync(
            input -> {
              order.add("inner");
              return WorkerResult.of(Map.of("inner", true));
            });
    WorkerFunction outer =
        new WorkerFunction.Sync(
            input -> {
              order.add("outer-start");
              var rt = WorkerExecutionContext.currentRuntime();
              var result = rt.execute(inner, input);
              order.add("outer-end");
              return result;
            });

    WorkerExecutionContext.setRuntime(runtime);
    WorkerResult result = runtime.execute(outer, Map.of());

    assertEquals(List.of("outer-start", "inner", "outer-end"), order);
    assertTrue((Boolean) result.output().get("inner"));
  }

  @Test
  void execute_unsupportedFunctionType_returnsFailed() {
    WorkerResult result = runtime.execute(WorkerFunction.NONE, Map.of());
    assertInstanceOf(WorkerOutcome.Failed.class, result.outcome());
  }

  @Test
  void spawnCase_existingDefinition_returnsCaseId() {
    UUID childId = UUID.randomUUID();
    CaseDefinition definition =
        CaseDefinition.builder().namespace("test").name("child").version("1.0.0").build();

    CaseDefinitionRegistry registry = new StubCaseDefinitionRegistry(definition);
    CaseHubRuntime caseHubRuntime = new StubCaseHubRuntime(childId);
    CaseInstanceCache emptyCache = new StubCaseInstanceCache(null, null);

    var rt = new DefaultWorkerRuntime(CASE_ID, caseHubRuntime, registry, emptyCache, null);
    UUID result = rt.spawnCase("child", Map.of("key", "value"));
    assertEquals(childId, result);
  }

  @Test
  void spawnCase_unknownDefinition_throws() {
    CaseDefinitionRegistry registry = new StubCaseDefinitionRegistry(null);

    var rt = new DefaultWorkerRuntime(CASE_ID, null, registry, null, null);
    assertThrows(IllegalArgumentException.class, () -> rt.spawnCase("unknown", Map.of()));
  }

  @Test
  void awaitCase_alreadyCompleted_returnsImmediately() {
    UUID childId = UUID.randomUUID();
    CaseCompletionTracker tracker = new CaseCompletionTracker();

    CaseInstance childInstance = new CaseInstance();
    childInstance.setUuid(childId);
    childInstance.setState(CaseStatus.COMPLETED);
    childInstance.setCaseContext(
        new io.casehub.engine.internal.context.CaseContextImpl(Map.of("result", "done")));

    CaseInstanceCache cache = new StubCaseInstanceCache(childId, childInstance);

    var rt = new DefaultWorkerRuntime(CASE_ID, null, null, cache, tracker);
    CaseContext result = rt.awaitCase(childId, Duration.ofSeconds(5));
    assertNotNull(result);
  }

  @Test
  void awaitCase_faultedCase_throwsCaseTerminatedException() {
    UUID childId = UUID.randomUUID();
    CaseCompletionTracker tracker = new CaseCompletionTracker();

    CaseInstance childInstance = new CaseInstance();
    childInstance.setUuid(childId);
    childInstance.setState(CaseStatus.FAULTED);
    childInstance.setCaseContext(new io.casehub.engine.internal.context.CaseContextImpl());

    CaseInstanceCache cache = new StubCaseInstanceCache(childId, childInstance);

    var rt = new DefaultWorkerRuntime(CASE_ID, null, null, cache, tracker);
    CaseTerminatedException ex =
        assertThrows(
            CaseTerminatedException.class, () -> rt.awaitCase(childId, Duration.ofSeconds(5)));
    assertEquals(childId, ex.caseId());
    assertEquals(CaseStatus.FAULTED, ex.terminalStatus());
  }

  // --- Test doubles ---

  private static class StubCaseDefinitionRegistry implements CaseDefinitionRegistry {
    private final CaseDefinition definition;

    StubCaseDefinitionRegistry(CaseDefinition definition) {
      this.definition = definition;
    }

    @Override
    public Uni<CaseMetaModel> registerCaseDefinition(CaseDefinition model) {
      return null;
    }

    @Override
    public CaseDefinition getCaseDefinition(CaseMetaModel def) {
      return null;
    }

    @Override
    public CaseMetaModel getCaseMetaModel(CaseDefinition caseDef) {
      return null;
    }

    @Override
    public Optional<CaseDefinition> findByName(String name) {
      if (definition != null && name.equals(definition.getName())) {
        return Optional.of(definition);
      }
      return Optional.empty();
    }
  }

  private static class StubCaseHubRuntime implements CaseHubRuntime {
    private final UUID childCaseId;

    StubCaseHubRuntime(UUID childCaseId) {
      this.childCaseId = childCaseId;
    }

    @Override
    public CompletionStage<UUID> startCase(CaseDefinition d) {
      return null;
    }

    @Override
    public CompletionStage<UUID> startCase(CaseDefinition d, Object i) {
      return null;
    }

    @Override
    public CompletionStage<UUID> startCase(
        CaseDefinition d, Object i, UUID p, PropagationContext pc) {
      return CompletableFuture.completedFuture(childCaseId);
    }

    @Override
    public CompletionStage<UUID> startCase(CaseDefinition d, Object i, Map<String, Object> s) {
      return null;
    }

    @Override
    public CompletionStage<UUID> startCase(
        CaseDefinition d, Object i, Map<String, Object> s, UUID p, PropagationContext pc) {
      return null;
    }

    @Override
    public CompletionStage<Void> signal(UUID id, String p, Object v) {
      return null;
    }

    @Override
    public void cancelCase(UUID id) {}

    @Override
    public void suspendCase(UUID id) {}

    @Override
    public void resumeCase(UUID id) {}

    @Override
    public CompletionStage<Object> query(UUID id, String p) {
      return null;
    }

    @Override
    public <T> CompletionStage<T> query(UUID id, String p, Class<T> c) {
      return null;
    }

    @Override
    public CompletionStage<List<CaseEventLogRecord>> eventLog(UUID id) {
      return null;
    }

    @Override
    public CompletionStage<List<CaseEventLogRecord>> eventLog(UUID id, Set<CaseHubEventType> t) {
      return null;
    }

    @Override
    public CompletionStage<List<CaseEventLogRecord>> eventLog(
        UUID id, Set<CaseHubEventType> t, Set<EventStreamType> s) {
      return null;
    }
  }

  private static class StubCaseInstanceCache implements CaseInstanceCache {
    private final UUID targetId;
    private final CaseInstance instance;

    StubCaseInstanceCache(UUID targetId, CaseInstance instance) {
      this.targetId = targetId;
      this.instance = instance;
    }

    @Override
    public void put(CaseInstance inst) {}

    @Override
    public CaseInstance get(UUID caseId) {
      return caseId.equals(targetId) ? instance : null;
    }

    @Override
    public void clear() {}

    @Override
    public List<CaseInstance> getAll() {
      return List.of();
    }
  }
}
