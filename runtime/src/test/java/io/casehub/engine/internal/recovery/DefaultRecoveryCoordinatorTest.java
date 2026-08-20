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
package io.casehub.engine.internal.recovery;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.casehub.api.model.Binding;
import io.casehub.api.model.CaseDefinition;
import io.casehub.api.model.ContextChangeTrigger;
import io.casehub.api.model.OutcomeType;
import io.casehub.api.model.RecoveryLevel;
import io.casehub.api.model.RecoveryOverride;
import io.casehub.api.model.RecoveryPolicy;
import io.casehub.api.model.TaskStatus;
import io.casehub.api.model.event.CaseHubEventType;
import io.casehub.api.spi.recovery.ErrorClassifier;
import io.casehub.engine.common.internal.model.CaseInstance;
import io.casehub.engine.common.internal.model.CaseMetaModel;
import io.casehub.engine.common.spi.CaseDefinitionRegistry;
import io.casehub.engine.common.spi.EventLogRepository;
import io.casehub.engine.common.spi.GoalDecomposer;
import io.casehub.engine.common.spi.PlanAdaptationEvaluator;
import io.casehub.engine.common.spi.cache.CaseInstanceCache;
import io.casehub.engine.common.spi.recovery.CompoundLockRegistry;
import io.casehub.engine.common.spi.recovery.RecoveryContext;
import io.casehub.engine.plan.execution.InMemoryPlanVersionStore;
import io.casehub.worker.api.Capability;
import io.casehub.worker.api.WorkerOutcome;
import io.vertx.core.eventbus.EventBus;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class DefaultRecoveryCoordinatorTest {

  private DefaultRecoveryCoordinator coordinator;
  private ErrorClassifier classifier;
  private CaseRecoveryStateRegistry stateRegistry;
  private InMemoryPlanVersionStore planVersionStore;
  private PlanAdaptationEvaluator adaptationEvaluator;
  private GoalDecomposer goalDecomposer;
  private CaseDefinitionRegistry definitionRegistry;
  private CaseInstanceCache caseInstanceCache;
  private EventLogRepository eventLogRepository;
  private EventBus eventBus;
  private CompoundLockRegistry lockRegistry;
  private io.casehub.engine.common.spi.PlanItemStore planItemStore;

  @BeforeEach
  void setUp() {
    classifier = mock(ErrorClassifier.class);
    stateRegistry = new CaseRecoveryStateRegistry();
    planVersionStore = new InMemoryPlanVersionStore();
    adaptationEvaluator = mock(PlanAdaptationEvaluator.class);
    goalDecomposer = mock(GoalDecomposer.class);
    definitionRegistry = mock(CaseDefinitionRegistry.class);
    caseInstanceCache = mock(CaseInstanceCache.class);
    eventLogRepository = mock(EventLogRepository.class);
    eventBus = mock(EventBus.class);
    lockRegistry = new CompoundLockRegistry();
    planItemStore = mock(io.casehub.engine.common.spi.PlanItemStore.class);

    coordinator =
        new DefaultRecoveryCoordinator(
            classifier,
            stateRegistry,
            planVersionStore,
            adaptationEvaluator,
            goalDecomposer,
            definitionRegistry,
            caseInstanceCache,
            eventLogRepository,
            eventBus,
            lockRegistry,
            planItemStore);
  }

  @Test
  void transientClassificationDoesNotEscalate() {
    when(classifier.classify(any())).thenReturn(RecoveryLevel.TRANSIENT);
    RecoveryContext ctx = buildContext();
    setupRecoveryEnabled(ctx);

    boolean handled = coordinator.handleFailure(ctx);

    assertThat(handled).isFalse();
    verify(adaptationEvaluator, never()).evaluateAdaptation(any(), any(), any(), any());
    verify(goalDecomposer, never()).decompose(any(), any(), any());
    assertThat(planVersionStore.getHistory(ctx.caseId(), ctx.tenancyId())).isEmpty();
  }

  @Test
  void reasoningClassificationTriggersLevel2Adaptation() {
    when(classifier.classify(any())).thenReturn(RecoveryLevel.REASONING);
    RecoveryContext ctx = buildContext();
    CaseDefinition def = setupRecoveryEnabled(ctx);

    boolean handled = coordinator.handleFailure(ctx);

    assertThat(handled).isTrue();
    verify(adaptationEvaluator)
        .evaluateAdaptation(
            eq(ctx.caseId()), eq(ctx.tenancyId()), eq(ctx.bindingName()), eq(TaskStatus.FAULTED));
    verify(eventLogRepository)
        .append(
            argThat(log -> log.getEventType() == CaseHubEventType.RECOVERY_ESCALATED),
            eq(ctx.tenancyId()));
  }

  @Test
  void fundamentalClassificationTriggersLevel3Replan() {
    when(classifier.classify(any())).thenReturn(RecoveryLevel.FUNDAMENTAL);
    RecoveryContext ctx = buildContext();
    setupRecoveryEnabled(ctx);

    boolean handled = coordinator.handleFailure(ctx);

    assertThat(handled).isTrue();
    verify(goalDecomposer).decompose(any(), any(), any());
    assertThat(stateRegistry.getOrCreate(ctx.caseId()).isReplanAttempted()).isTrue();
    assertThat(planVersionStore.getHistory(ctx.caseId(), ctx.tenancyId())).hasSize(1);
    verify(eventLogRepository)
        .append(
            argThat(log -> log.getEventType() == CaseHubEventType.RECOVERY_REPLAN),
            eq(ctx.tenancyId()));
  }

  @Test
  void recoveryDisabledReturnsFalse() {
    RecoveryContext ctx = buildContext();
    setupRecoveryDisabled(ctx);

    boolean handled = coordinator.handleFailure(ctx);

    assertThat(handled).isFalse();
    verify(classifier, never()).classify(any());
  }

  @Test
  void nullRecoveryPolicyReturnsFalse() {
    RecoveryContext ctx = buildContext();
    setupNullRecoveryPolicy(ctx);

    boolean handled = coordinator.handleFailure(ctx);

    assertThat(handled).isFalse();
    verify(classifier, never()).classify(any());
  }

  @Test
  void skipRecoveryOverrideReturnsFalse() {
    when(classifier.classify(any())).thenReturn(RecoveryLevel.REASONING);
    RecoveryContext ctx = buildContext();
    setupRecoveryEnabledWithSkipOverride(ctx);

    boolean handled = coordinator.handleFailure(ctx);

    assertThat(handled).isFalse();
    verify(adaptationEvaluator, never()).evaluateAdaptation(any(), any(), any(), any());
  }

  @Test
  void skipRecoveryForSpecificOutcomeReturnsFalse() {
    when(classifier.classify(any())).thenReturn(RecoveryLevel.REASONING);
    RecoveryContext ctx =
        new RecoveryContext(
            UUID.randomUUID(),
            "tenant-1",
            "binding-1",
            "worker-1",
            "capability-1",
            new WorkerOutcome.Expired<>("timeout"),
            null,
            3,
            UUID.randomUUID());
    setupRecoveryEnabledWithSkipExpired(ctx);

    boolean handled = coordinator.handleFailure(ctx);

    assertThat(handled).isFalse();
  }

  @Test
  void maxLevelOverridePreventsFundamentalEscalation() {
    when(classifier.classify(any())).thenReturn(RecoveryLevel.FUNDAMENTAL);
    RecoveryContext ctx = buildContext();
    setupRecoveryEnabledWithMaxLevelReasoning(ctx);

    boolean handled = coordinator.handleFailure(ctx);

    assertThat(handled).isFalse();
    verify(goalDecomposer, never()).decompose(any(), any(), any());
  }

  @Test
  void replanAlreadyAttemptedReturnsFalse() {
    when(classifier.classify(any())).thenReturn(RecoveryLevel.FUNDAMENTAL);
    RecoveryContext ctx = buildContext();
    setupRecoveryEnabled(ctx);
    stateRegistry.getOrCreate(ctx.caseId()).markReplanAttempted();

    boolean handled = coordinator.handleFailure(ctx);

    assertThat(handled).isFalse();
    verify(goalDecomposer, never()).decompose(any(), any(), any());
  }

  @Test
  void recoveryTracksBindingState() {
    when(classifier.classify(any())).thenReturn(RecoveryLevel.REASONING);
    RecoveryContext ctx = buildContext();
    setupRecoveryEnabled(ctx);

    coordinator.handleFailure(ctx);

    var bindingState = stateRegistry.getOrCreate(ctx.caseId()).getOrCreate(ctx.bindingName());
    assertThat(bindingState.currentLevel()).isEqualTo(RecoveryLevel.REASONING);
    assertThat(bindingState.retryCount()).isEqualTo(1);
    assertThat(bindingState.excludedAgents()).contains(ctx.workerName());
  }

  @Test
  void caseInstanceNotFoundReturnsFalse() {
    RecoveryContext ctx = buildContext();
    when(caseInstanceCache.get(ctx.caseId())).thenReturn(null);

    boolean handled = coordinator.handleFailure(ctx);

    assertThat(handled).isFalse();
  }

  private RecoveryContext buildContext() {
    return new RecoveryContext(
        UUID.randomUUID(),
        "tenant-1",
        "binding-1",
        "worker-1",
        "capability-1",
        new WorkerOutcome.Failed<>("reasoning error"),
        null,
        3,
        UUID.randomUUID());
  }

  private CaseDefinition setupRecoveryEnabled(RecoveryContext ctx) {
    CaseDefinition def =
        CaseDefinition.builder()
            .namespace("test")
            .name("recovery")
            .version("1.0")
            .recoveryPolicy(RecoveryPolicy.DEFAULT)
            .capabilities(Capability.of("capability-1", ".", "."))
            .bindings(
                Binding.builder()
                    .name("binding-1")
                    .capability(Capability.of("capability-1", ".", "."))
                    .on(new ContextChangeTrigger(".ready"))
                    .build())
            .build();
    CaseMetaModel meta = new CaseMetaModel();
    meta.setNamespace("test");
    meta.setName("recovery");
    meta.setVersion("1.0");
    CaseInstance instance = new CaseInstance();
    instance.setUuid(ctx.caseId());
    instance.tenancyId = ctx.tenancyId();
    instance.setCaseMetaModel(meta);
    when(caseInstanceCache.get(ctx.caseId())).thenReturn(instance);
    when(definitionRegistry.getCaseDefinition(meta)).thenReturn(def);
    return def;
  }

  private void setupRecoveryDisabled(RecoveryContext ctx) {
    CaseDefinition def =
        CaseDefinition.builder()
            .namespace("test")
            .name("recovery")
            .version("1.0")
            .recoveryPolicy(RecoveryPolicy.DISABLED)
            .build();
    CaseMetaModel meta = new CaseMetaModel();
    CaseInstance instance = new CaseInstance();
    instance.setUuid(ctx.caseId());
    instance.setCaseMetaModel(meta);
    when(caseInstanceCache.get(ctx.caseId())).thenReturn(instance);
    when(definitionRegistry.getCaseDefinition(meta)).thenReturn(def);
  }

  private void setupNullRecoveryPolicy(RecoveryContext ctx) {
    CaseDefinition def =
        CaseDefinition.builder().namespace("test").name("recovery").version("1.0").build();
    CaseMetaModel meta = new CaseMetaModel();
    CaseInstance instance = new CaseInstance();
    instance.setUuid(ctx.caseId());
    instance.setCaseMetaModel(meta);
    when(caseInstanceCache.get(ctx.caseId())).thenReturn(instance);
    when(definitionRegistry.getCaseDefinition(meta)).thenReturn(def);
  }

  private void setupRecoveryEnabledWithSkipOverride(RecoveryContext ctx) {
    CaseDefinition def =
        CaseDefinition.builder()
            .namespace("test")
            .name("recovery")
            .version("1.0")
            .recoveryPolicy(RecoveryPolicy.DEFAULT)
            .capabilities(Capability.of("capability-1", ".", "."))
            .bindings(
                Binding.builder()
                    .name("binding-1")
                    .capability(Capability.of("capability-1", ".", "."))
                    .on(new ContextChangeTrigger(".ready"))
                    .recoveryOverride(RecoveryOverride.skip())
                    .build())
            .build();
    CaseMetaModel meta = new CaseMetaModel();
    CaseInstance instance = new CaseInstance();
    instance.setUuid(ctx.caseId());
    instance.setCaseMetaModel(meta);
    when(caseInstanceCache.get(ctx.caseId())).thenReturn(instance);
    when(definitionRegistry.getCaseDefinition(meta)).thenReturn(def);
  }

  private void setupRecoveryEnabledWithSkipExpired(RecoveryContext ctx) {
    CaseDefinition def =
        CaseDefinition.builder()
            .namespace("test")
            .name("recovery")
            .version("1.0")
            .recoveryPolicy(RecoveryPolicy.DEFAULT)
            .capabilities(Capability.of("capability-1", ".", "."))
            .bindings(
                Binding.builder()
                    .name("binding-1")
                    .capability(Capability.of("capability-1", ".", "."))
                    .on(new ContextChangeTrigger(".ready"))
                    .recoveryOverride(
                        new RecoveryOverride(null, null, null, false, Set.of(OutcomeType.EXPIRED)))
                    .build())
            .build();
    CaseMetaModel meta = new CaseMetaModel();
    CaseInstance instance = new CaseInstance();
    instance.setUuid(ctx.caseId());
    instance.setCaseMetaModel(meta);
    when(caseInstanceCache.get(ctx.caseId())).thenReturn(instance);
    when(definitionRegistry.getCaseDefinition(meta)).thenReturn(def);
  }

  private void setupRecoveryEnabledWithMaxLevelReasoning(RecoveryContext ctx) {
    CaseDefinition def =
        CaseDefinition.builder()
            .namespace("test")
            .name("recovery")
            .version("1.0")
            .recoveryPolicy(RecoveryPolicy.DEFAULT)
            .capabilities(Capability.of("capability-1", ".", "."))
            .bindings(
                Binding.builder()
                    .name("binding-1")
                    .capability(Capability.of("capability-1", ".", "."))
                    .on(new ContextChangeTrigger(".ready"))
                    .recoveryOverride(
                        new RecoveryOverride(null, null, RecoveryLevel.REASONING, false, Set.of()))
                    .build())
            .build();
    CaseMetaModel meta = new CaseMetaModel();
    CaseInstance instance = new CaseInstance();
    instance.setUuid(ctx.caseId());
    instance.setCaseMetaModel(meta);
    when(caseInstanceCache.get(ctx.caseId())).thenReturn(instance);
    when(definitionRegistry.getCaseDefinition(meta)).thenReturn(def);
  }
}
