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
package io.casehub.engine.internal.worker.scope;

import static org.assertj.core.api.Assertions.assertThat;

import io.casehub.api.model.LifecycleScope;
import io.casehub.api.model.Participation;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class ScopedWorkerRegistryTest {

  private final io.casehub.engine.common.internal.worker.scope.ScopedWorkerRegistry registry =
      new io.casehub.engine.common.internal.worker.scope.ScopedWorkerRegistry();

  private io.casehub.engine.common.internal.worker.scope.ScopedWorkerSession.Reinvoked
      reinvokedSession(
          UUID caseId,
          String bindingName,
          String executorName,
          LifecycleScope scope,
          Participation p) {
    return new io.casehub.engine.common.internal.worker.scope.ScopedWorkerSession.Reinvoked(
        bindingName,
        caseId,
        executorName,
        scope,
        p,
        new AtomicReference<>(Map.of()),
        new AtomicReference<>(null));
  }

  @Test
  void register_and_get() {
    UUID caseId = UUID.randomUUID();
    var session =
        reinvokedSession(
            caseId, "binding-a", "pi-1", LifecycleScope.COMPOUND, Participation.PARTICIPANT);
    registry.register(
        new io.casehub.engine.common.internal.worker.scope.ScopedWorkerRegistry.ScopeKey(
            caseId, "binding-a"),
        session);

    assertThat(registry.get(caseId, "binding-a")).contains(session);
  }

  @Test
  void get_returns_empty_when_not_registered() {
    assertThat(registry.get(UUID.randomUUID(), "missing")).isEmpty();
  }

  @Test
  void terminateByCase_removes_all_sessions_for_case() {
    UUID caseId = UUID.randomUUID();
    var s1 =
        reinvokedSession(caseId, "b1", "pi-1", LifecycleScope.COMPOUND, Participation.PARTICIPANT);
    var s2 = reinvokedSession(caseId, "b2", "pi-2", LifecycleScope.CASE, Participation.COMPANION);
    registry.register(
        new io.casehub.engine.common.internal.worker.scope.ScopedWorkerRegistry.ScopeKey(
            caseId, "b1"),
        s1);
    registry.register(
        new io.casehub.engine.common.internal.worker.scope.ScopedWorkerRegistry.ScopeKey(
            caseId, "b2"),
        s2);

    registry.terminateByCase(caseId);

    assertThat(registry.get(caseId, "b1")).isEmpty();
    assertThat(registry.get(caseId, "b2")).isEmpty();
  }

  @Test
  void terminateByCase_does_not_affect_other_cases() {
    UUID caseA = UUID.randomUUID();
    UUID caseB = UUID.randomUUID();
    var sessionA =
        reinvokedSession(caseA, "b1", "pi-1", LifecycleScope.COMPOUND, Participation.PARTICIPANT);
    var sessionB =
        reinvokedSession(caseB, "b1", "pi-2", LifecycleScope.COMPOUND, Participation.PARTICIPANT);
    registry.register(
        new io.casehub.engine.common.internal.worker.scope.ScopedWorkerRegistry.ScopeKey(
            caseA, "b1"),
        sessionA);
    registry.register(
        new io.casehub.engine.common.internal.worker.scope.ScopedWorkerRegistry.ScopeKey(
            caseB, "b1"),
        sessionB);

    registry.terminateByCase(caseA);

    assertThat(registry.get(caseA, "b1")).isEmpty();
    assertThat(registry.get(caseB, "b1")).contains(sessionB);
  }

  @Test
  void register_replaces_existing_session() {
    UUID caseId = UUID.randomUUID();
    var s1 =
        reinvokedSession(caseId, "b1", "pi-1", LifecycleScope.COMPOUND, Participation.PARTICIPANT);
    var s2 =
        reinvokedSession(caseId, "b1", "pi-2", LifecycleScope.COMPOUND, Participation.PARTICIPANT);
    var key =
        new io.casehub.engine.common.internal.worker.scope.ScopedWorkerRegistry.ScopeKey(
            caseId, "b1");
    registry.register(key, s1);
    registry.register(key, s2);

    assertThat(registry.get(caseId, "b1")).contains(s2);
  }

  @Test
  void terminateByScope_removes_only_owned_bindings() {
    UUID caseId = UUID.randomUUID();
    var s1 =
        reinvokedSession(
            caseId, "owned", "pi-1", LifecycleScope.COMPOUND, Participation.PARTICIPANT);
    var s2 =
        reinvokedSession(caseId, "unowned", "pi-2", LifecycleScope.CASE, Participation.COMPANION);
    registry.register(
        new io.casehub.engine.common.internal.worker.scope.ScopedWorkerRegistry.ScopeKey(
            caseId, "owned"),
        s1);
    registry.register(
        new io.casehub.engine.common.internal.worker.scope.ScopedWorkerRegistry.ScopeKey(
            caseId, "unowned"),
        s2);

    registry.terminateByScope(caseId, "compound-1", Set.of("owned"));

    assertThat(registry.get(caseId, "owned")).isEmpty();
    assertThat(registry.get(caseId, "unowned")).contains(s2);
  }

  @Test
  void register_reinvoked_session_with_executorName_and_lastInputDataHash() {
    UUID caseId = UUID.randomUUID();
    var session =
        new io.casehub.engine.common.internal.worker.scope.ScopedWorkerSession.Reinvoked(
            "binding-a",
            caseId,
            "worker-1",
            LifecycleScope.COMPOUND,
            Participation.PARTICIPANT,
            new AtomicReference<>(Map.of()),
            new AtomicReference<>(null));
    registry.register(
        new io.casehub.engine.common.internal.worker.scope.ScopedWorkerRegistry.ScopeKey(
            caseId, "binding-a"),
        session);
    assertThat(registry.get(caseId, "binding-a")).isPresent();
    assertThat(registry.get(caseId, "binding-a").get().executorName()).isEqualTo("worker-1");
  }

  @Test
  void register_persistent_session_without_workerThread() {
    UUID caseId = UUID.randomUUID();
    var mailbox =
        new java.util.concurrent.LinkedBlockingQueue<
            io.casehub.engine.common.internal.worker.scope.ContextEvent>();
    var session =
        new io.casehub.engine.common.internal.worker.scope.ScopedWorkerSession.Persistent(
            "binding-p", caseId, "worker-2", LifecycleScope.CASE, Participation.COMPANION, mailbox);
    registry.register(
        new io.casehub.engine.common.internal.worker.scope.ScopedWorkerRegistry.ScopeKey(
            caseId, "binding-p"),
        session);
    assertThat(registry.get(caseId, "binding-p")).isPresent();
    assertThat(registry.get(caseId, "binding-p").get().executorName()).isEqualTo("worker-2");
  }

  @Test
  void executionLock_returns_same_lock_for_same_key() {
    UUID caseId = UUID.randomUUID();
    var key =
        new io.casehub.engine.common.internal.worker.scope.ScopedWorkerRegistry.ScopeKey(
            caseId, "b1");
    java.util.concurrent.locks.ReentrantLock lock1 = registry.executionLock(key);
    java.util.concurrent.locks.ReentrantLock lock2 = registry.executionLock(key);
    assertThat(lock1).isSameAs(lock2);
  }

  @Test
  void terminateByCase_cleans_up_execution_locks() {
    UUID caseId = UUID.randomUUID();
    var key =
        new io.casehub.engine.common.internal.worker.scope.ScopedWorkerRegistry.ScopeKey(
            caseId, "b1");
    java.util.concurrent.locks.ReentrantLock lockBefore = registry.executionLock(key);
    var session =
        new io.casehub.engine.common.internal.worker.scope.ScopedWorkerSession.Reinvoked(
            "b1",
            caseId,
            "worker-1",
            LifecycleScope.COMPOUND,
            Participation.PARTICIPANT,
            new AtomicReference<>(Map.of()),
            new AtomicReference<>(null));
    registry.register(key, session);
    registry.terminateByCase(caseId);
    java.util.concurrent.locks.ReentrantLock lockAfter = registry.executionLock(key);
    assertThat(lockAfter).isNotSameAs(lockBefore);
  }
}
