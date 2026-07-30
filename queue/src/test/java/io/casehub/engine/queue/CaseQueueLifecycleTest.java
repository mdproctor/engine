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
package io.casehub.engine.queue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.casehub.api.context.ContextLayer;
import io.casehub.api.model.CaseDefinition;
import io.casehub.api.model.CaseStatus;
import io.casehub.engine.common.internal.model.CaseInstance;
import io.casehub.engine.common.internal.model.CaseMetaModel;
import io.casehub.engine.common.spi.CaseDefinitionRegistry;
import io.casehub.engine.common.spi.CaseInstanceRepository;
import io.casehub.engine.common.spi.event.CaseLifecycleEvent;
import io.casehub.engine.internal.context.CaseContextImpl;
import io.casehub.engine.queue.entry.CaseQueueEntryManager;
import io.casehub.engine.queue.event.CaseQueueEvent;
import io.casehub.engine.queue.label.CaseLabelEvaluator;
import io.casehub.engine.queue.model.CaseQueueEntry;
import io.casehub.engine.queue.model.QueueEntryStatus;
import io.casehub.engine.queue.service.CaseQueueService;
import io.casehub.engine.queue.spi.CaseQueueEntryStore;
import io.casehub.engine.queue.view.CaseQueueViewManager;
import io.casehub.platform.api.expression.CompiledExpression;
import io.casehub.platform.api.label.LabelAction;
import io.casehub.platform.api.label.LabelRule;
import io.casehub.platform.api.view.SubjectViewSpec;
import io.casehub.platform.view.SubjectViewEvaluator;
import io.casehub.platform.view.SubjectViewOrchestrator;
import io.casehub.platform.view.inmem.InMemorySubjectViewStore;
import io.casehub.platform.view.inmem.InMemoryViewMembershipTracker;
import jakarta.enterprise.event.Event;
import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class CaseQueueLifecycleTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private CaseLabelEvaluator evaluator;
  private CaseQueueEntryManager entryManager;
  private CaseQueueService queueService;
  private CaseQueueEntryStore entryStore;
  private CaseQueueViewManager viewManager;
  private CaseDefinitionRegistry definitionRegistry;
  private CaseInstanceRepository caseInstanceRepo;
  private SubjectViewOrchestrator orchestrator;

  @BeforeEach
  @SuppressWarnings("unchecked")
  void setUp() throws Exception {
    InMemorySubjectViewStore viewStore = new InMemorySubjectViewStore();
    InMemoryViewMembershipTracker tracker = new InMemoryViewMembershipTracker();
    SubjectViewEvaluator viewEvaluator = new SubjectViewEvaluator();
    orchestrator = new SubjectViewOrchestrator();
    inject(orchestrator, "evaluator", viewEvaluator);
    inject(orchestrator, "viewStore", viewStore);
    inject(orchestrator, "tracker", tracker);

    viewManager = new CaseQueueViewManager(orchestrator, viewStore);

    definitionRegistry = mock(CaseDefinitionRegistry.class);
    caseInstanceRepo = mock(CaseInstanceRepository.class);
    when(caseInstanceRepo.update(any(), any()))
        .thenAnswer(inv -> (inv.getArgument(0, CaseInstance.class)));

    io.casehub.engine.queue.store.InMemoryCaseQueueEntryStore memStore =
        new io.casehub.engine.queue.store.InMemoryCaseQueueEntryStore();
    entryStore = memStore;

    evaluator = new CaseLabelEvaluator();
    inject(evaluator, "definitionRegistry", definitionRegistry);
    inject(evaluator, "caseInstanceRepository", caseInstanceRepo);
    inject(evaluator, "views", orchestrator);

    Event<CaseQueueEvent> evaluatorQueueEvents = mock(Event.class);
    inject(evaluator, "queueEvents", evaluatorQueueEvents);

    entryManager = new CaseQueueEntryManager();
    inject(entryManager, "store", entryStore);
    Event<io.casehub.engine.queue.event.CaseQueueEntryRevoked> revokedBus = mock(Event.class);
    inject(entryManager, "revokedEvents", revokedBus);
    doAnswer(inv -> null).when(revokedBus).fireAsync(any());

    doAnswer(
            inv -> {
              CaseQueueEvent event = inv.getArgument(0);
              entryManager.onQueueEvent(event);
              return null;
            })
        .when(evaluatorQueueEvents)
        .fire(any());

    queueService = new CaseQueueService();
    inject(queueService, "store", entryStore);
    Event<io.casehub.engine.queue.event.CaseQueueEntryClaimed> claimedBus = mock(Event.class);
    Event<io.casehub.engine.queue.event.CaseQueueEntryReleased> releasedBus = mock(Event.class);
    Event<io.casehub.engine.queue.event.CaseQueueEntryEscalated> escalatedBus = mock(Event.class);
    inject(queueService, "claimedEvents", claimedBus);
    inject(queueService, "releasedEvents", releasedBus);
    inject(queueService, "escalatedEvents", escalatedBus);
    doAnswer(inv -> null).when(claimedBus).fireAsync(any());
    doAnswer(inv -> null).when(releasedBus).fireAsync(any());
    doAnswer(inv -> null).when(escalatedBus).fireAsync(any());
  }

  @Test
  void fullLifecycle_labelRules_queue_claim_contextChange_terminal() {
    SubjectViewSpec highPriorityView =
        viewManager.ensureQueueView("High Priority", "tenant-1", "priority/high");

    CaseDefinition definition =
        CaseDefinition.builder()
            .namespace("test")
            .name("lifecycle-case")
            .version("1.0")
            .labelRule(
                new LabelRule(
                    "high-priority",
                    condition(ctx -> "HIGH".equals(ctx.get("severity"))),
                    List.of(new LabelAction.Add("priority/high"))))
            .build();

    UUID caseId = UUID.randomUUID();
    CaseInstance instance = buildInstance(caseId, "tenant-1", Map.of("severity", "HIGH"));
    wireDefinition(instance, definition);
    wireRepoRead(instance);

    evaluator.onCaseLifecycle(lifecycleEvent(instance, "CaseStarted"));

    assertThat(instance.getLabels()).containsExactly("priority/high");
    List<CaseQueueEntry> pending = queueService.findPending(highPriorityView.id(), "tenant-1");
    assertThat(pending).hasSize(1);
    assertThat(pending.get(0).getStatus()).isEqualTo(QueueEntryStatus.PENDING);

    CaseQueueEntry claimed = queueService.claim(pending.get(0).getId(), "tenant-1", "analyst-1");
    assertThat(claimed.getStatus()).isEqualTo(QueueEntryStatus.CLAIMED);
    assertThat(claimed.getAssignedTo()).isEqualTo("analyst-1");

    ((CaseContextImpl) instance.getCaseContext())
        .writableLayer(ContextLayer.WORKING)
        .set("severity", "LOW");
    evaluator.onCaseLifecycle(lifecycleEvent(instance, "ContextChanged"));

    assertThat(instance.getLabels()).isEmpty();
    CaseQueueEntry afterRemoval = entryStore.findByCaseAndView(caseId, highPriorityView.id()).get();
    assertThat(afterRemoval.getStatus()).isEqualTo(QueueEntryStatus.REVOKED);

    CaseLifecycleEvent terminalEvent =
        CaseLifecycleEvent.of(
            caseId, "tenant-1", "CompleteCase", "CaseCompleted", "COMPLETED", null, "System", null);
    evaluator.onCaseLifecycle(terminalEvent);

    assertThat(instance.getLabels()).isEmpty();
  }

  @Test
  void contextChange_adds_and_removes_labels_across_evaluations() {
    viewManager.ensureQueueView("Triage", "tenant-1", "triage/**");
    viewManager.ensureQueueView("Resolved", "tenant-1", "resolved/**");

    CaseDefinition definition =
        CaseDefinition.builder()
            .namespace("test")
            .name("multi-label-case")
            .version("1.0")
            .labelRule(
                new LabelRule(
                    "triage",
                    condition(ctx -> "pending".equals(ctx.get("status"))),
                    List.of(new LabelAction.Add("triage/entity"))))
            .labelRule(
                new LabelRule(
                    "resolved",
                    condition(ctx -> "resolved".equals(ctx.get("status"))),
                    List.of(new LabelAction.Add("resolved/entity"))))
            .build();

    UUID caseId = UUID.randomUUID();
    CaseInstance instance = buildInstance(caseId, "tenant-1", Map.of("status", "pending"));
    wireDefinition(instance, definition);
    wireRepoRead(instance);

    evaluator.onCaseLifecycle(lifecycleEvent(instance, "CaseStarted"));
    assertThat(instance.getLabels()).containsExactly("triage/entity");

    ((CaseContextImpl) instance.getCaseContext())
        .writableLayer(ContextLayer.WORKING)
        .set("status", "resolved");
    evaluator.onCaseLifecycle(lifecycleEvent(instance, "ContextChanged"));
    assertThat(instance.getLabels()).containsExactly("resolved/entity");
    assertThat(instance.getLabels()).doesNotContain("triage/entity");
  }

  private CaseInstance buildInstance(UUID caseId, String tenancyId, Map<String, Object> context) {
    CaseInstance ci = new CaseInstance();
    ci.setUuid(caseId);
    ci.tenancyId = tenancyId;
    ci.setState(CaseStatus.RUNNING);
    CaseMetaModel metaModel = new CaseMetaModel();
    metaModel.setNamespace("test");
    metaModel.setName("lifecycle-case");
    metaModel.setVersion("1.0");
    ci.setCaseMetaModel(metaModel);
    CaseContextImpl ctx = new CaseContextImpl();
    context.forEach((k, v) -> ctx.writableLayer(ContextLayer.WORKING).set(k, v));
    ci.setCaseContext(ctx);
    return ci;
  }

  private void wireDefinition(CaseInstance instance, CaseDefinition definition) {
    when(definitionRegistry.getCaseDefinition(instance.getCaseMetaModel())).thenReturn(definition);
  }

  private void wireRepoRead(CaseInstance instance) {
    when(caseInstanceRepo.findByUuid(instance.getUuid(), instance.tenancyId))
        .thenReturn((instance));
  }

  private CaseLifecycleEvent lifecycleEvent(CaseInstance instance, String eventType) {
    return CaseLifecycleEvent.of(instance, "Engine", eventType, null, "System", null);
  }

  private static CompiledExpression<Map<String, Object>, Boolean> condition(
      java.util.function.Function<Map<String, Object>, Boolean> fn) {
    return new CompiledExpression<>() {
      @Override
      public String type() {
        return "test";
      }

      @Override
      public Boolean eval(Map<String, Object> ctx) {
        return fn.apply(ctx);
      }
    };
  }

  private static void inject(Object target, String fieldName, Object value) throws Exception {
    Field field = target.getClass().getDeclaredField(fieldName);
    field.setAccessible(true);
    field.set(target, value);
  }
}
