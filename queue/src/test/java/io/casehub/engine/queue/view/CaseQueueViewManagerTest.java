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
package io.casehub.engine.queue.view;

import static org.assertj.core.api.Assertions.assertThat;

import io.casehub.platform.api.view.SubjectViewSpec;
import io.casehub.platform.view.SubjectViewEvaluator;
import io.casehub.platform.view.SubjectViewOrchestrator;
import io.casehub.platform.view.inmem.InMemorySubjectViewStore;
import io.casehub.platform.view.inmem.InMemoryViewMembershipTracker;
import java.lang.reflect.Field;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class CaseQueueViewManagerTest {

  private CaseQueueViewManager manager;
  private InMemorySubjectViewStore viewStore;

  @BeforeEach
  void setUp() throws Exception {
    viewStore = new InMemorySubjectViewStore();
    SubjectViewOrchestrator orchestrator = new SubjectViewOrchestrator();
    inject(orchestrator, "evaluator", new SubjectViewEvaluator());
    inject(orchestrator, "viewStore", viewStore);
    inject(orchestrator, "tracker", new InMemoryViewMembershipTracker());
    manager = new CaseQueueViewManager(orchestrator, viewStore);
  }

  @Test
  void ensureQueueView_createsView() {
    SubjectViewSpec spec = manager.ensureQueueView("High Priority", "tenant-1", "priority/high");
    assertThat(spec).isNotNull();
    assertThat(spec.name()).isEqualTo("High Priority");
    assertThat(spec.tenancyId()).isEqualTo("tenant-1");
    assertThat(spec.labelPattern()).isEqualTo("priority/high");
  }

  @Test
  void ensureQueueView_idempotent_sameUUID() {
    SubjectViewSpec first = manager.ensureQueueView("High Priority", "tenant-1", "priority/high");
    SubjectViewSpec second = manager.ensureQueueView("High Priority", "tenant-1", "priority/high");
    assertThat(first.id()).isEqualTo(second.id());
  }

  @Test
  void ensureQueueView_differentTenant_differentUUID() {
    SubjectViewSpec t1 = manager.ensureQueueView("High Priority", "tenant-1", "priority/high");
    SubjectViewSpec t2 = manager.ensureQueueView("High Priority", "tenant-2", "priority/high");
    assertThat(t1.id()).isNotEqualTo(t2.id());
  }

  @Test
  void deleteQueueView() {
    SubjectViewSpec spec = manager.ensureQueueView("High Priority", "tenant-1", "priority/high");
    assertThat(manager.deleteQueueView(spec.id())).isTrue();
    assertThat(viewStore.findById(spec.id())).isEmpty();
  }

  @Test
  void deleteQueueView_notFound_returnsFalse() {
    assertThat(manager.deleteQueueView(UUID.randomUUID())).isFalse();
  }

  private static void inject(Object target, String fieldName, Object value) throws Exception {
    Field field = target.getClass().getDeclaredField(fieldName);
    field.setAccessible(true);
    field.set(target, value);
  }
}
