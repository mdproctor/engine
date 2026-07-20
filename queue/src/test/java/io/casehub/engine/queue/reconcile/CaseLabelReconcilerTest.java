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
package io.casehub.engine.queue.reconcile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.casehub.api.context.ContextLayer;
import io.casehub.api.model.CaseDefinition;
import io.casehub.api.model.CaseStatus;
import io.casehub.engine.common.internal.model.CaseInstance;
import io.casehub.engine.common.internal.model.CaseMetaModel;
import io.casehub.engine.common.spi.CaseDefinitionRegistry;
import io.casehub.engine.common.spi.ReactiveCaseInstanceRepository;
import io.casehub.engine.internal.context.CaseContextImpl;
import io.casehub.engine.queue.event.CaseQueueEvent;
import io.casehub.engine.queue.event.CaseQueueEventType;
import io.casehub.platform.api.expression.CompiledExpression;
import io.casehub.platform.api.label.LabelAction;
import io.casehub.platform.api.label.LabelRule;
import io.casehub.platform.api.view.CrossTenantSubjectViewStore;
import io.casehub.platform.api.view.SubjectViewEvent;
import io.casehub.platform.api.view.ViewEventType;
import io.casehub.platform.view.SubjectViewOrchestrator;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.event.Event;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class CaseLabelReconcilerTest {

  private CaseLabelReconciler reconciler;
  private CaseDefinitionRegistry definitionRegistry;
  private ReactiveCaseInstanceRepository caseInstanceRepo;
  private SubjectViewOrchestrator views;
  private CrossTenantSubjectViewStore crossTenantViewStore;
  private final List<CaseQueueEvent> firedEvents = new ArrayList<>();

  @BeforeEach
  @SuppressWarnings("unchecked")
  void setUp() throws Exception {
    reconciler = new CaseLabelReconciler();
    definitionRegistry = mock(CaseDefinitionRegistry.class);
    caseInstanceRepo = mock(ReactiveCaseInstanceRepository.class);
    views = mock(SubjectViewOrchestrator.class);
    crossTenantViewStore = mock(CrossTenantSubjectViewStore.class);
    Event<CaseQueueEvent> queueEvents = mock(Event.class);

    inject(reconciler, "definitionRegistry", definitionRegistry);
    inject(reconciler, "caseInstanceRepository", caseInstanceRepo);
    inject(reconciler, "views", views);
    inject(reconciler, "crossTenantViewStore", crossTenantViewStore);
    inject(reconciler, "queueEvents", queueEvents);

    firedEvents.clear();
    doAnswer(
            inv -> {
              firedEvents.add(inv.getArgument(0));
              return null;
            })
        .when(queueEvents)
        .fire(any());
  }

  @Test
  void reconciles_active_cases_labels() {
    when(crossTenantViewStore.findDistinctTenancyIds()).thenReturn(List.of("tenant-1"));

    UUID caseId = UUID.randomUUID();
    CaseInstance instance =
        buildInstance(caseId, "tenant-1", CaseStatus.RUNNING, Map.of("severity", "HIGH"));

    CaseDefinition definition =
        buildDefinition(
            new LabelRule(
                "high",
                conditionMatching("severity", "HIGH"),
                List.of(new LabelAction.Add("priority/high"))));
    wireDefinition(instance, definition);

    when(caseInstanceRepo.findByStatus(CaseStatus.RUNNING, "tenant-1"))
        .thenReturn(Uni.createFrom().item(List.of(instance)));
    when(caseInstanceRepo.findByStatus(eq(CaseStatus.STARTING), any()))
        .thenReturn(Uni.createFrom().item(List.of()));
    when(caseInstanceRepo.findByStatus(eq(CaseStatus.WAITING), any()))
        .thenReturn(Uni.createFrom().item(List.of()));
    when(caseInstanceRepo.findByStatus(eq(CaseStatus.SUSPENDED), any()))
        .thenReturn(Uni.createFrom().item(List.of()));
    when(caseInstanceRepo.update(any(), any()))
        .thenAnswer(inv -> Uni.createFrom().item(inv.getArgument(0, CaseInstance.class)));

    UUID viewId = UUID.randomUUID();
    when(views.evaluateAndTrack(eq(caseId), eq("tenant-1"), eq(Set.of("priority/high"))))
        .thenReturn(
            List.of(
                new SubjectViewEvent(
                    caseId, viewId, "High Priority", ViewEventType.ADDED, "tenant-1")));

    reconciler.reconcile(null);

    assertThat(instance.getLabels()).containsExactly("priority/high");
    assertThat(firedEvents).hasSize(1);
    assertThat(firedEvents.get(0).eventType()).isEqualTo(CaseQueueEventType.ADDED);
  }

  @Test
  void no_tenancies_skips_silently() {
    when(crossTenantViewStore.findDistinctTenancyIds()).thenReturn(List.of());

    reconciler.reconcile(null);

    assertThat(firedEvents).isEmpty();
  }

  @Test
  void no_labelRules_skips_case() {
    when(crossTenantViewStore.findDistinctTenancyIds()).thenReturn(List.of("tenant-1"));

    UUID caseId = UUID.randomUUID();
    CaseInstance instance = buildInstance(caseId, "tenant-1", CaseStatus.RUNNING, Map.of());
    CaseDefinition definition = buildDefinition();
    wireDefinition(instance, definition);

    when(caseInstanceRepo.findByStatus(eq(CaseStatus.RUNNING), eq("tenant-1")))
        .thenReturn(Uni.createFrom().item(List.of(instance)));
    when(caseInstanceRepo.findByStatus(eq(CaseStatus.STARTING), any()))
        .thenReturn(Uni.createFrom().item(List.of()));
    when(caseInstanceRepo.findByStatus(eq(CaseStatus.WAITING), any()))
        .thenReturn(Uni.createFrom().item(List.of()));
    when(caseInstanceRepo.findByStatus(eq(CaseStatus.SUSPENDED), any()))
        .thenReturn(Uni.createFrom().item(List.of()));

    reconciler.reconcile(null);

    assertThat(firedEvents).isEmpty();
  }

  private CaseInstance buildInstance(
      UUID caseId, String tenancyId, CaseStatus status, Map<String, Object> context) {
    CaseInstance ci = new CaseInstance();
    ci.setUuid(caseId);
    ci.tenancyId = tenancyId;
    ci.setState(status);
    CaseMetaModel metaModel = new CaseMetaModel();
    metaModel.setNamespace("test");
    metaModel.setName("test-case");
    metaModel.setVersion("1.0");
    ci.setCaseMetaModel(metaModel);
    CaseContextImpl ctx = new CaseContextImpl();
    context.forEach((k, v) -> ctx.writableLayer(ContextLayer.WORKING).set(k, v));
    ci.setCaseContext(ctx);
    return ci;
  }

  private CaseDefinition buildDefinition(LabelRule... rules) {
    return CaseDefinition.builder()
        .namespace("test")
        .name("test-case")
        .version("1.0")
        .labelRules(List.of(rules))
        .build();
  }

  private void wireDefinition(CaseInstance instance, CaseDefinition definition) {
    when(definitionRegistry.getCaseDefinition(instance.getCaseMetaModel())).thenReturn(definition);
  }

  private static CompiledExpression<Map<String, Object>, Boolean> conditionMatching(
      String key, Object expected) {
    return new CompiledExpression<>() {
      @Override
      public String type() {
        return "test";
      }

      @Override
      public Boolean eval(Map<String, Object> ctx) {
        return expected.equals(ctx.get(key));
      }
    };
  }

  private static void inject(Object target, String fieldName, Object value) throws Exception {
    Field field = target.getClass().getDeclaredField(fieldName);
    field.setAccessible(true);
    field.set(target, value);
  }
}
