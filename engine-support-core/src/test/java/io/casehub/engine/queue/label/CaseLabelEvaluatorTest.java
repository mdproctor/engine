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
package io.casehub.engine.queue.label;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
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
import io.casehub.engine.queue.event.CaseQueueEvent;
import io.casehub.engine.queue.event.CaseQueueEventType;
import io.casehub.platform.api.expression.CompiledExpression;
import io.casehub.platform.api.label.LabelAction;
import io.casehub.platform.api.label.LabelRule;
import io.casehub.platform.api.view.SubjectViewEvent;
import io.casehub.platform.api.view.ViewEventType;
import io.casehub.platform.view.SubjectViewOrchestrator;
import jakarta.enterprise.event.Event;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class CaseLabelEvaluatorTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private CaseLabelEvaluator evaluator;
  private CaseDefinitionRegistry definitionRegistry;
  private CaseInstanceRepository caseInstanceRepo;
  private SubjectViewOrchestrator views;
  private Event<CaseQueueEvent> queueEvents;
  private final List<CaseQueueEvent> firedEvents = new ArrayList<>();

  @BeforeEach
  @SuppressWarnings("unchecked")
  void setUp() throws Exception {
    evaluator = new CaseLabelEvaluator();
    definitionRegistry = mock(CaseDefinitionRegistry.class);
    caseInstanceRepo = mock(CaseInstanceRepository.class);
    views = mock(SubjectViewOrchestrator.class);
    queueEvents = mock(Event.class);

    inject(evaluator, "definitionRegistry", definitionRegistry);
    inject(evaluator, "caseInstanceRepository", caseInstanceRepo);
    inject(evaluator, "views", views);
    inject(evaluator, "queueEvents", queueEvents);

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
  void labelRules_applied_and_labels_updated() {
    UUID caseId = UUID.randomUUID();
    CaseInstance instance = buildInstance(caseId, "tenant-1", Map.of("severity", "HIGH"));

    CaseDefinition definition =
        buildDefinition(
            new LabelRule(
                "high",
                conditionMatching("severity", "HIGH"),
                List.of(new LabelAction.Add("priority/high"))));
    wireDefinition(instance, definition);
    wireRepoRead(instance);

    UUID viewId = UUID.randomUUID();
    when(views.evaluateAndTrack(eq(caseId), eq("tenant-1"), eq(Set.of("priority/high"))))
        .thenReturn(
            List.of(
                new SubjectViewEvent(
                    caseId, viewId, "High Priority", ViewEventType.ADDED, "tenant-1")));

    evaluator.onCaseLifecycle(lifecycleEvent(instance, "CaseStarted"));

    assertThat(instance.getLabels()).containsExactly("priority/high");
    verify(caseInstanceRepo).update(instance, "tenant-1");
    assertThat(firedEvents).hasSize(1);
    assertThat(firedEvents.get(0).eventType()).isEqualTo(CaseQueueEventType.ADDED);
    assertThat(firedEvents.get(0).queueViewId()).isEqualTo(viewId);
  }

  @Test
  void no_labelRules_skips_evaluation() {
    UUID caseId = UUID.randomUUID();
    CaseInstance instance = buildInstance(caseId, "tenant-1", Map.of());
    CaseDefinition definition = buildDefinition();
    wireDefinition(instance, definition);
    wireRepoRead(instance);

    evaluator.onCaseLifecycle(lifecycleEvent(instance, "CaseStarted"));

    verify(views, never()).evaluateAndTrack(any(), any(), any());
    verify(caseInstanceRepo, never()).update(any(), any());
  }

  @Test
  void labels_unchanged_skips_orchestrator() {
    UUID caseId = UUID.randomUUID();
    CaseInstance instance = buildInstance(caseId, "tenant-1", Map.of("severity", "LOW"));
    instance.setLabels(new LinkedHashSet<>());

    CaseDefinition definition =
        buildDefinition(
            new LabelRule(
                "high",
                conditionMatching("severity", "HIGH"),
                List.of(new LabelAction.Add("priority/high"))));
    wireDefinition(instance, definition);
    wireRepoRead(instance);

    evaluator.onCaseLifecycle(lifecycleEvent(instance, "ContextChanged"));

    assertThat(instance.getLabels()).isEmpty();
    verify(views, never()).evaluateAndTrack(any(), any(), any());
  }

  @Test
  void terminal_status_clears_labels() {
    UUID caseId = UUID.randomUUID();
    CaseInstance instance = buildInstance(caseId, "tenant-1", Map.of("severity", "HIGH"));
    instance.setLabels(new LinkedHashSet<>(Set.of("priority/high")));

    CaseDefinition definition =
        buildDefinition(
            new LabelRule(
                "high",
                conditionMatching("severity", "HIGH"),
                List.of(new LabelAction.Add("priority/high"))));
    wireDefinition(instance, definition);
    wireRepoRead(instance);

    when(views.evaluateAndTrack(eq(caseId), eq("tenant-1"), eq(Set.of())))
        .thenReturn(
            List.of(
                new SubjectViewEvent(
                    caseId,
                    UUID.randomUUID(),
                    "High Priority",
                    ViewEventType.REMOVED,
                    "tenant-1")));

    CaseLifecycleEvent event =
        CaseLifecycleEvent.of(
            caseId, "tenant-1", "CompleteCase", "CaseCompleted", "COMPLETED", null, "System", null);
    evaluator.onCaseLifecycle(event);

    assertThat(instance.getLabels()).isEmpty();
    verify(views).evaluateAndTrack(eq(caseId), eq("tenant-1"), eq(Set.of()));
  }

  @Test
  void clean_slate_recomputation() {
    UUID caseId = UUID.randomUUID();
    CaseInstance instance = buildInstance(caseId, "tenant-1", Map.of("severity", "LOW"));
    instance.setLabels(new LinkedHashSet<>(Set.of("priority/high")));

    CaseDefinition definition =
        buildDefinition(
            new LabelRule(
                "low",
                conditionMatching("severity", "LOW"),
                List.of(new LabelAction.Add("priority/low"))));
    wireDefinition(instance, definition);
    wireRepoRead(instance);

    when(views.evaluateAndTrack(eq(caseId), eq("tenant-1"), eq(Set.of("priority/low"))))
        .thenReturn(List.of());

    evaluator.onCaseLifecycle(lifecycleEvent(instance, "ContextChanged"));

    assertThat(instance.getLabels()).containsExactly("priority/low");
    assertThat(instance.getLabels()).doesNotContain("priority/high");
  }

  @Test
  void remove_action_negates_add() {
    UUID caseId = UUID.randomUUID();
    CaseInstance instance =
        buildInstance(caseId, "tenant-1", Map.of("severity", "HIGH", "override", true));

    CaseDefinition definition =
        buildDefinition(
            new LabelRule(
                "high",
                conditionMatching("severity", "HIGH"),
                List.of(new LabelAction.Add("priority/high"))),
            new LabelRule(
                "override", conditionTrue(), List.of(new LabelAction.Remove("priority/high"))));
    wireDefinition(instance, definition);
    wireRepoRead(instance);

    evaluator.onCaseLifecycle(lifecycleEvent(instance, "CaseStarted"));

    assertThat(instance.getLabels()).isEmpty();
    verify(views, never()).evaluateAndTrack(any(), any(), any());
  }

  private CaseInstance buildInstance(UUID caseId, String tenancyId, Map<String, Object> context) {
    CaseInstance ci = new CaseInstance();
    ci.setUuid(caseId);
    ci.tenancyId = tenancyId;
    ci.setState(CaseStatus.RUNNING);
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
    CaseDefinition def =
        CaseDefinition.builder()
            .namespace("test")
            .name("test-case")
            .version("1.0")
            .labelRules(List.of(rules))
            .build();
    return def;
  }

  private void wireDefinition(CaseInstance instance, CaseDefinition definition) {
    when(definitionRegistry.getCaseDefinition(instance.getCaseMetaModel())).thenReturn(definition);
  }

  private void wireRepoRead(CaseInstance instance) {
    when(caseInstanceRepo.findByUuid(instance.getUuid(), instance.tenancyId))
        .thenReturn((instance));
    when(caseInstanceRepo.update(any(), any()))
        .thenAnswer(inv -> (inv.getArgument(0, CaseInstance.class)));
  }

  private CaseLifecycleEvent lifecycleEvent(CaseInstance instance, String eventType) {
    return CaseLifecycleEvent.of(instance, "Engine", eventType, null, "System", null);
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

  private static CompiledExpression<Map<String, Object>, Boolean> conditionTrue() {
    return new CompiledExpression<>() {
      @Override
      public String type() {
        return "test";
      }

      @Override
      public Boolean eval(Map<String, Object> ctx) {
        return true;
      }
    };
  }

  private static void inject(Object target, String fieldName, Object value) throws Exception {
    Field field = target.getClass().getDeclaredField(fieldName);
    field.setAccessible(true);
    field.set(target, value);
  }
}
