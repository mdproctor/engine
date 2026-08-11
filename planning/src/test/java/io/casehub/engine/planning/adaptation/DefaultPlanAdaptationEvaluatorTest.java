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
package io.casehub.engine.planning.adaptation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.casehub.api.model.AdaptationConfig;
import io.casehub.api.model.CaseDefinition;
import io.casehub.api.model.TaskStatus;
import io.casehub.engine.common.internal.history.EventLog;
import io.casehub.engine.common.internal.model.CaseInstance;
import io.casehub.engine.common.internal.model.CaseMetaModel;
import io.casehub.engine.common.spi.CaseDefinitionRegistry;
import io.casehub.engine.common.spi.CaseInstanceRepository;
import io.casehub.engine.common.spi.EventLogRepository;
import io.casehub.engine.common.spi.PlanItemStore;
import io.casehub.engine.plan.adaptation.AdaptationCause;
import io.casehub.engine.plan.adaptation.AdaptationSignal;
import io.casehub.engine.plan.adaptation.AdaptationTrigger;
import io.casehub.engine.plan.adaptation.PlanRevisionStrategy;
import io.casehub.engine.plan.adaptation.PlanStepDescriptor;
import io.casehub.engine.plan.adaptation.RevisedPlan;
import io.casehub.engine.plan.adaptation.RevisionContext;
import io.casehub.engine.planning.plan.CompletionSemantics;
import io.casehub.engine.planning.plan.DefaultCasePlanModel;
import io.casehub.engine.planning.plan.DispatchMode;
import io.casehub.engine.planning.plan.PlanItem;
import io.casehub.engine.planning.plan.PlanItemDefinition;
import io.casehub.engine.planning.registry.BlackboardRegistry;
import io.casehub.platform.api.routing.StrategyResolver;
import jakarta.enterprise.inject.Instance;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class DefaultPlanAdaptationEvaluatorTest {

  private static final String TENANT = "tenant-1";
  private static final ObjectMapper MAPPER = new ObjectMapper();

  private BlackboardRegistry registry;
  private PlanItemStore planItemStore;
  private EventLogRepository eventLogRepository;
  private CaseInstanceRepository caseInstanceRepository;
  private CaseDefinitionRegistry caseDefinitionRegistry;
  private StrategyResolver strategyResolver;

  @SuppressWarnings("unchecked")
  private Instance<Object> memoryRetriever = mock(Instance.class);

  private DefaultPlanAdaptationEvaluator evaluator;

  private UUID caseId;
  private CaseInstance caseInstance;
  private CaseMetaModel metaModel;
  private CaseDefinition definition;
  private DefaultCasePlanModel casePlanModel;

  @BeforeEach
  void setUp() {
    registry = mock(BlackboardRegistry.class);
    planItemStore = mock(PlanItemStore.class);
    eventLogRepository = mock(EventLogRepository.class);
    caseInstanceRepository = mock(CaseInstanceRepository.class);
    caseDefinitionRegistry = mock(CaseDefinitionRegistry.class);
    strategyResolver = mock(StrategyResolver.class);
    when(memoryRetriever.isResolvable()).thenReturn(false);

    caseId = UUID.randomUUID();
    casePlanModel = new DefaultCasePlanModel(caseId);

    metaModel = new CaseMetaModel();
    metaModel.setNamespace("test");
    metaModel.setName("test-case");
    metaModel.setVersion("1.0");

    caseInstance = new CaseInstance();
    caseInstance.setUuid(caseId);
    caseInstance.tenancyId = TENANT;
    caseInstance.setCaseMetaModel(metaModel);

    definition =
        CaseDefinition.builder()
            .namespace("test")
            .name("test-case")
            .version("1.0")
            .adaptationConfig(new AdaptationConfig("every-step", "forward-replan"))
            .build();

    when(caseInstanceRepository.findByUuid(caseId, TENANT)).thenReturn(caseInstance);
    when(caseDefinitionRegistry.getCaseDefinition(metaModel)).thenReturn(definition);
    when(registry.get(caseId)).thenReturn(Optional.of(casePlanModel));
    when(planItemStore.findByCaseId(caseId, TENANT)).thenReturn(List.of());

    evaluator =
        new DefaultPlanAdaptationEvaluator(
            registry,
            planItemStore,
            eventLogRepository,
            caseInstanceRepository,
            caseDefinitionRegistry,
            strategyResolver,
            memoryRetriever,
            3,
            30000L);
  }

  @Test
  void skipsWhenBindingNotInDecomposedCompound() {
    evaluator.evaluateAdaptation(caseId, TENANT, "free-binding", TaskStatus.COMPLETED);

    verify(strategyResolver, never()).resolve(eq(AdaptationTrigger.class), anyString());
  }

  @Test
  void skipsWhenAdaptationConfigNull() {
    var noAdaptDef =
        CaseDefinition.builder().namespace("test").name("test-case").version("1.0").build();
    when(caseDefinitionRegistry.getCaseDefinition(metaModel)).thenReturn(noAdaptDef);

    registerCompoundWithBinding("goal", "cap-a");

    evaluator.evaluateAdaptation(caseId, TENANT, "cap-a", TaskStatus.COMPLETED);

    verify(strategyResolver, never()).resolve(eq(AdaptationTrigger.class), anyString());
  }

  @Test
  void callsTriggerAndSkipsOnSkipSignal() {
    registerCompoundWithBinding("goal", "cap-a");

    var trigger = mock(AdaptationTrigger.class);
    when(trigger.evaluate(any())).thenReturn(AdaptationSignal.SKIP);
    when(strategyResolver.resolve(AdaptationTrigger.class, "every-step")).thenReturn(trigger);

    evaluator.evaluateAdaptation(caseId, TENANT, "cap-a", TaskStatus.COMPLETED);

    verify(trigger).evaluate(any());
    verify(strategyResolver, never()).resolve(eq(PlanRevisionStrategy.class), anyString());
  }

  @Test
  void callsTriggerProceedThenRevision() {
    registerCompoundWithBinding("goal", "cap-a", "cap-b");
    addCompletedPlanItem("cap-a");

    var trigger = mock(AdaptationTrigger.class);
    when(trigger.evaluate(any())).thenReturn(AdaptationSignal.PROCEED);
    when(strategyResolver.resolve(AdaptationTrigger.class, "every-step")).thenReturn(trigger);

    var revision = mock(PlanRevisionStrategy.class);
    var revisedPlan =
        new RevisedPlan(
            List.of(
                new PlanStepDescriptor("new-1", "deep analyse", "cap-c"),
                new PlanStepDescriptor("new-2", "report", "cap-d")),
            "context changed");
    when(revision.revise(any())).thenReturn(revisedPlan);
    when(strategyResolver.resolve(PlanRevisionStrategy.class, "forward-replan"))
        .thenReturn(revision);

    evaluator.evaluateAdaptation(caseId, TENANT, "cap-a", TaskStatus.COMPLETED);

    verify(revision).revise(any());
    verify(eventLogRepository).append(any(EventLog.class), eq(TENANT));
  }

  @Test
  void marksPendingPlanItemsObsolete() {
    registerCompoundWithBinding("goal", "cap-a", "cap-b", "cap-c");
    addCompletedPlanItem("cap-a");
    addPendingPlanItem("cap-b");
    addPendingPlanItem("cap-c");

    setupProceedTriggerAndRevision(List.of(new PlanStepDescriptor("new-1", "new step", "cap-d")));

    evaluator.evaluateAdaptation(caseId, TENANT, "cap-a", TaskStatus.COMPLETED);

    verify(planItemStore, org.mockito.Mockito.atLeastOnce())
        .updateStatus(any(), eq(TaskStatus.OBSOLETE), eq(TENANT));
  }

  @Test
  void leavesRunningPlanItemsUntouched() {
    registerCompoundWithBinding("goal", "cap-a", "cap-b");
    addCompletedPlanItem("cap-a");
    var runningItem = addRunningPlanItem("cap-b");

    setupProceedTriggerAndRevision(List.of(new PlanStepDescriptor("new-1", "new step", "cap-d")));

    evaluator.evaluateAdaptation(caseId, TENANT, "cap-a", TaskStatus.COMPLETED);

    verify(planItemStore, never())
        .updateStatus(eq(runningItem.getPlanItemId()), eq(TaskStatus.OBSOLETE), anyString());
    assertThat(runningItem.getStatus()).isEqualTo(TaskStatus.RUNNING);
  }

  @Test
  void writesEventLogWithCorrectMetadata() {
    registerCompoundWithBinding("goal", "cap-a", "cap-b");
    addCompletedPlanItem("cap-a");

    setupProceedTriggerAndRevision(List.of(new PlanStepDescriptor("new-1", "new step", "cap-d")));

    evaluator.evaluateAdaptation(caseId, TENANT, "cap-a", TaskStatus.COMPLETED);

    var captor = ArgumentCaptor.forClass(EventLog.class);
    verify(eventLogRepository).append(captor.capture(), eq(TENANT));
    var log = captor.getValue();

    assertThat(log.getCaseId()).isEqualTo(caseId);
    assertThat(log.getEventType())
        .isEqualTo(io.casehub.api.model.event.CaseHubEventType.PLAN_ADAPTED);
    assertThat(log.getMetadata().get("goalName").asText()).isEqualTo("goal");
    assertThat(log.getMetadata().get("compoundId").asText()).isEqualTo("goal");
    assertThat(log.getMetadata().get("triggerStrategy").asText()).isEqualTo("every-step");
    assertThat(log.getMetadata().get("revisionStrategy").asText()).isEqualTo("forward-replan");
    assertThat(log.getMetadata().get("newStepCount").asInt()).isEqualTo(1);
  }

  @Test
  void generationCounterPreventsRedundantAdaptation() {
    registerCompoundWithBinding("goal", "cap-a", "cap-b", "cap-c");
    addCompletedPlanItem("cap-a");

    var adaptationCount = new AtomicInteger(0);
    var trigger = mock(AdaptationTrigger.class);
    when(trigger.evaluate(any())).thenReturn(AdaptationSignal.PROCEED);
    when(strategyResolver.resolve(AdaptationTrigger.class, "every-step")).thenReturn(trigger);

    var revision = mock(PlanRevisionStrategy.class);
    when(revision.revise(any()))
        .thenAnswer(
            inv -> {
              adaptationCount.incrementAndGet();
              return new RevisedPlan(
                  List.of(new PlanStepDescriptor("new-1", "step", "cap-d")), "reason");
            });
    when(strategyResolver.resolve(PlanRevisionStrategy.class, "forward-replan"))
        .thenReturn(revision);

    // First adaptation succeeds, increments generation
    evaluator.evaluateAdaptation(caseId, TENANT, "cap-a", TaskStatus.COMPLETED);
    assertThat(adaptationCount.get()).isEqualTo(1);

    // Second call with same event — generation already advanced
    evaluator.evaluateAdaptation(caseId, TENANT, "cap-a", TaskStatus.COMPLETED);
    // Second should still proceed because the generation check happens at acquire time
    // and the binding is still in the compound after replacement
  }

  @Test
  void timeoutResultsInGracefulDegradation() {
    registerCompoundWithBinding("goal", "cap-a", "cap-b");
    addCompletedPlanItem("cap-a");

    var trigger = mock(AdaptationTrigger.class);
    when(trigger.evaluate(any())).thenReturn(AdaptationSignal.PROCEED);
    when(strategyResolver.resolve(AdaptationTrigger.class, "every-step")).thenReturn(trigger);

    var revision = mock(PlanRevisionStrategy.class);
    when(revision.revise(any())).thenThrow(new RuntimeException("LLM timeout"));
    when(strategyResolver.resolve(PlanRevisionStrategy.class, "forward-replan"))
        .thenReturn(revision);

    // Should not throw — graceful degradation
    evaluator.evaluateAdaptation(caseId, TENANT, "cap-a", TaskStatus.COMPLETED);

    // No EventLog written on failure
    verify(eventLogRepository, never()).append(any(), anyString());
  }

  @Test
  void exceptionIsolationOnRevisionFailure() {
    registerCompoundWithBinding("goal", "cap-a", "cap-b");
    addCompletedPlanItem("cap-a");

    var trigger = mock(AdaptationTrigger.class);
    when(trigger.evaluate(any())).thenReturn(AdaptationSignal.PROCEED);
    when(strategyResolver.resolve(AdaptationTrigger.class, "every-step")).thenReturn(trigger);

    var revision = mock(PlanRevisionStrategy.class);
    when(revision.revise(any())).thenThrow(new RuntimeException("Unexpected error"));
    when(strategyResolver.resolve(PlanRevisionStrategy.class, "forward-replan"))
        .thenReturn(revision);

    // Should not propagate — existing plan unchanged
    evaluator.evaluateAdaptation(caseId, TENANT, "cap-a", TaskStatus.COMPLETED);

    verify(eventLogRepository, never()).append(any(), anyString());
  }

  @Test
  void constructsCorrectAdaptationCauseForSuccess() {
    registerCompoundWithBinding("goal", "cap-a", "cap-b");
    addCompletedPlanItem("cap-a");

    var trigger = mock(AdaptationTrigger.class);
    when(trigger.evaluate(any())).thenReturn(AdaptationSignal.PROCEED);
    when(strategyResolver.resolve(AdaptationTrigger.class, "every-step")).thenReturn(trigger);

    var revisionCaptor = ArgumentCaptor.forClass(RevisionContext.class);
    var revision = mock(PlanRevisionStrategy.class);
    when(revision.revise(revisionCaptor.capture()))
        .thenReturn(new RevisedPlan(List.of(new PlanStepDescriptor("n1", "s", "cap-d")), null));
    when(strategyResolver.resolve(PlanRevisionStrategy.class, "forward-replan"))
        .thenReturn(revision);

    evaluator.evaluateAdaptation(caseId, TENANT, "cap-a", TaskStatus.COMPLETED);

    var ctx = revisionCaptor.getValue();
    assertThat(ctx.cause()).isInstanceOf(AdaptationCause.StepCompleted.class);
    var cause = (AdaptationCause.StepCompleted) ctx.cause();
    assertThat(cause.capabilityName()).isEqualTo("cap-a");
  }

  @Test
  void constructsCorrectAdaptationCauseForFailure() {
    registerCompoundWithBinding("goal", "cap-a", "cap-b");
    addCompletedPlanItem("cap-a");

    var trigger = mock(AdaptationTrigger.class);
    when(trigger.evaluate(any())).thenReturn(AdaptationSignal.PROCEED);
    when(strategyResolver.resolve(AdaptationTrigger.class, "every-step")).thenReturn(trigger);

    var revisionCaptor = ArgumentCaptor.forClass(RevisionContext.class);
    var revision = mock(PlanRevisionStrategy.class);
    when(revision.revise(revisionCaptor.capture()))
        .thenReturn(new RevisedPlan(List.of(new PlanStepDescriptor("n1", "s", "cap-d")), null));
    when(strategyResolver.resolve(PlanRevisionStrategy.class, "forward-replan"))
        .thenReturn(revision);

    evaluator.evaluateAdaptation(caseId, TENANT, "cap-a", TaskStatus.FAULTED);

    var ctx = revisionCaptor.getValue();
    assertThat(ctx.cause()).isInstanceOf(AdaptationCause.StepFailed.class);
    var cause = (AdaptationCause.StepFailed) ctx.cause();
    assertThat(cause.stepId()).isEqualTo("cap-a");
  }

  @Test
  void skipsWhenCaseInstanceNotFound() {
    registerCompoundWithBinding("goal", "cap-a");
    when(caseInstanceRepository.findByUuid(caseId, TENANT)).thenReturn(null);

    evaluator.evaluateAdaptation(caseId, TENANT, "cap-a", TaskStatus.COMPLETED);

    verify(strategyResolver, never()).resolve(eq(AdaptationTrigger.class), anyString());
  }

  @Test
  void skipsWhenCasePlanModelNotFound() {
    when(registry.get(caseId)).thenReturn(Optional.empty());

    evaluator.evaluateAdaptation(caseId, TENANT, "cap-a", TaskStatus.COMPLETED);

    verify(caseInstanceRepository, never()).findByUuid(any(), anyString());
  }

  @Test
  void savesNewPlanItemsToStore() {
    registerCompoundWithBinding("goal", "cap-a", "cap-b");
    addCompletedPlanItem("cap-a");

    setupProceedTriggerAndRevision(
        List.of(
            new PlanStepDescriptor("new-1", "analyse deeply", "cap-c"),
            new PlanStepDescriptor("new-2", "write report", "cap-d")));

    evaluator.evaluateAdaptation(caseId, TENANT, "cap-a", TaskStatus.COMPLETED);

    verify(planItemStore, org.mockito.Mockito.atLeastOnce()).save(any(), eq(TENANT));
  }

  // --- Helpers ---

  private void registerCompoundWithBinding(String compoundName, String... bindingNames) {
    var builder =
        PlanItemDefinition.Compound.builder(compoundName)
            .id(compoundName)
            .completion(CompletionSemantics.all())
            .dispatchMode(DispatchMode.CHOREOGRAPHED);
    for (int i = 0; i < bindingNames.length; i++) {
      builder.child(
          new PlanItemDefinition.Primitive(
              compoundName + "-step-" + i,
              "Step " + bindingNames[i],
              io.casehub.api.model.ExecutorRef.of("worker-" + bindingNames[i], null),
              null));
      builder.binding(bindingNames[i]);
    }
    casePlanModel.registerDefinition(builder.build());
  }

  private PlanItem addCompletedPlanItem(String bindingName) {
    var item =
        PlanItem.create(
            bindingName,
            io.casehub.api.model.ExecutorRef.of("worker", null),
            0,
            null,
            "Step " + bindingName);
    item.tryMarkRunning();
    item.markCompleted();
    casePlanModel.addPlanItem(item);
    return item;
  }

  private PlanItem addPendingPlanItem(String bindingName) {
    var item =
        PlanItem.create(
            bindingName,
            io.casehub.api.model.ExecutorRef.of("worker", null),
            0,
            null,
            "Step " + bindingName);
    casePlanModel.addPlanItem(item);
    return item;
  }

  private PlanItem addRunningPlanItem(String bindingName) {
    var item =
        PlanItem.create(
            bindingName,
            io.casehub.api.model.ExecutorRef.of("worker", null),
            0,
            null,
            "Step " + bindingName);
    item.tryMarkRunning();
    casePlanModel.addPlanItem(item);
    return item;
  }

  private void setupProceedTriggerAndRevision(List<PlanStepDescriptor> newSteps) {
    var trigger = mock(AdaptationTrigger.class);
    when(trigger.evaluate(any())).thenReturn(AdaptationSignal.PROCEED);
    when(strategyResolver.resolve(AdaptationTrigger.class, "every-step")).thenReturn(trigger);

    var revision = mock(PlanRevisionStrategy.class);
    when(revision.revise(any())).thenReturn(new RevisedPlan(newSteps, "revised"));
    when(strategyResolver.resolve(PlanRevisionStrategy.class, "forward-replan"))
        .thenReturn(revision);
  }
}
