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
package io.casehub.engine.internal.context;

import static org.assertj.core.api.Assertions.assertThat;

import io.casehub.api.context.ContextChangeEvent;
import io.casehub.api.context.Subscription;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Tests for the per-key and any-change listener functionality on {@link CaseContextImpl}.
 *
 * <p>Covers: correct old/new values, error isolation, re-entrant writes (no deadlock), engineSet()
 * bypass, setAll() per-key events, subscription cancellation.
 */
@DisplayName("CaseContext change listeners")
class CaseContextListenerTest {

  private CaseContextImpl ctx;

  @BeforeEach
  void setUp() {
    ctx = new CaseContextImpl();
  }

  // ── Basic listener invocation ──────────────────────────────────────────────

  @Nested
  @DisplayName("set()")
  class Set {

    @Test
    @DisplayName("onChange fires with correct old and new values")
    void onChange_firesWithCorrectValues() {
      ctx.set("status", "pending");

      List<ContextChangeEvent> events = new CopyOnWriteArrayList<>();
      ctx.onChange("status", events::add);

      ctx.set("status", "active");

      assertThat(events).hasSize(1);
      assertThat(events.get(0).key()).isEqualTo("status");
      assertThat(events.get(0).oldValue()).isEqualTo("pending");
      assertThat(events.get(0).newValue()).isEqualTo("active");
    }

    @Test
    @DisplayName("onChange fires with null oldValue on first set")
    void onChange_nullOldValueOnFirstSet() {
      List<ContextChangeEvent> events = new CopyOnWriteArrayList<>();
      ctx.onChange("newKey", events::add);

      ctx.set("newKey", "hello");

      assertThat(events).hasSize(1);
      assertThat(events.get(0).oldValue()).isNull();
      assertThat(events.get(0).newValue()).isEqualTo("hello");
    }

    @Test
    @DisplayName("onChange does not fire when value unchanged")
    void onChange_doesNotFireWhenUnchanged() {
      ctx.set("key", "value");

      List<ContextChangeEvent> events = new CopyOnWriteArrayList<>();
      ctx.onChange("key", events::add);

      ctx.set("key", "value"); // same value

      assertThat(events).isEmpty();
    }

    @Test
    @DisplayName("onChange does not fire for unrelated keys")
    void onChange_doesNotFireForUnrelatedKeys() {
      List<ContextChangeEvent> events = new CopyOnWriteArrayList<>();
      ctx.onChange("watched", events::add);

      ctx.set("other", "value");

      assertThat(events).isEmpty();
    }
  }

  // ── onAnyChange ────────────────────────────────────────────────────────────

  @Nested
  @DisplayName("onAnyChange()")
  class AnyChange {

    @Test
    @DisplayName("onAnyChange fires for any key change")
    void onAnyChange_firesForAnyKey() {
      List<ContextChangeEvent> events = new CopyOnWriteArrayList<>();
      ctx.onAnyChange(events::add);

      ctx.set("a", 1);
      ctx.set("b", 2);

      assertThat(events).hasSize(2);
      assertThat(events.get(0).key()).isEqualTo("a");
      assertThat(events.get(1).key()).isEqualTo("b");
    }

    @Test
    @DisplayName("per-key listeners fire before any-change listeners")
    void perKeyBeforeAnyChange() {
      List<String> order = Collections.synchronizedList(new ArrayList<>());
      ctx.onChange("key", e -> order.add("per-key"));
      ctx.onAnyChange(e -> order.add("any-change"));

      ctx.set("key", "value");

      assertThat(order).containsExactly("per-key", "any-change");
    }
  }

  // ── setAll() ───────────────────────────────────────────────────────────────

  @Nested
  @DisplayName("setAll()")
  class SetAll {

    @Test
    @DisplayName("fires one event per changed key")
    void firesOneEventPerChangedKey() {
      ctx.set("existing", "old");

      List<ContextChangeEvent> events = new CopyOnWriteArrayList<>();
      ctx.onAnyChange(events::add);

      ctx.setAll(Map.of("existing", "new", "fresh", "value"));

      assertThat(events).hasSize(2);
      assertThat(events)
          .extracting(ContextChangeEvent::key)
          .containsExactlyInAnyOrder("existing", "fresh");

      ContextChangeEvent existingEvent =
          events.stream().filter(e -> e.key().equals("existing")).findFirst().orElseThrow();
      assertThat(existingEvent.oldValue()).isEqualTo("old");
      assertThat(existingEvent.newValue()).isEqualTo("new");

      ContextChangeEvent freshEvent =
          events.stream().filter(e -> e.key().equals("fresh")).findFirst().orElseThrow();
      assertThat(freshEvent.oldValue()).isNull();
      assertThat(freshEvent.newValue()).isEqualTo("value");
    }

    @Test
    @DisplayName("does not fire for unchanged keys in setAll")
    void doesNotFireForUnchangedKeys() {
      ctx.set("same", "value");

      List<ContextChangeEvent> events = new CopyOnWriteArrayList<>();
      ctx.onAnyChange(events::add);

      ctx.setAll(Map.of("same", "value", "new", "data"));

      assertThat(events).hasSize(1);
      assertThat(events.get(0).key()).isEqualTo("new");
    }
  }

  // ── remove() ───────────────────────────────────────────────────────────────

  @Nested
  @DisplayName("remove()")
  class Remove {

    @Test
    @DisplayName("fires with null newValue on removal")
    void firesWithNullNewValue() {
      ctx.set("key", "value");

      List<ContextChangeEvent> events = new CopyOnWriteArrayList<>();
      ctx.onChange("key", events::add);

      ctx.remove("key");

      assertThat(events).hasSize(1);
      assertThat(events.get(0).oldValue()).isEqualTo("value");
      assertThat(events.get(0).newValue()).isNull();
    }

    @Test
    @DisplayName("does not fire when removing non-existent key")
    void doesNotFireForMissingKey() {
      List<ContextChangeEvent> events = new CopyOnWriteArrayList<>();
      ctx.onChange("missing", events::add);

      ctx.remove("missing");

      assertThat(events).isEmpty();
    }
  }

  // ── clear() ────────────────────────────────────────────────────────────────

  @Nested
  @DisplayName("clear()")
  class Clear {

    @Test
    @DisplayName("fires one event per key with null newValue")
    void firesPerKeyOnClear() {
      ctx.set("a", 1);
      ctx.set("b", 2);

      List<ContextChangeEvent> events = new CopyOnWriteArrayList<>();
      ctx.onAnyChange(events::add);

      ctx.clear();

      assertThat(events).hasSize(2);
      assertThat(events).allMatch(e -> e.newValue() == null);
      assertThat(events).extracting(ContextChangeEvent::key).containsExactlyInAnyOrder("a", "b");
    }
  }

  // ── update() ───────────────────────────────────────────────────────────────

  @Nested
  @DisplayName("update()")
  class Update {

    @Test
    @DisplayName("fires with old and new values")
    void firesWithOldAndNew() {
      ctx.set("count", 5);

      List<ContextChangeEvent> events = new CopyOnWriteArrayList<>();
      ctx.onChange("count", events::add);

      ctx.update("count", old -> (int) old + 1);

      assertThat(events).hasSize(1);
      assertThat(events.get(0).oldValue()).isEqualTo(5);
      assertThat(events.get(0).newValue()).isEqualTo(6);
    }
  }

  // ── computeIfAbsent() ─────────────────────────────────────────────────────

  @Nested
  @DisplayName("computeIfAbsent()")
  class ComputeIfAbsent {

    @Test
    @DisplayName("fires when value is computed (key absent)")
    void firesWhenComputed() {
      List<ContextChangeEvent> events = new CopyOnWriteArrayList<>();
      ctx.onChange("lazy", events::add);

      ctx.computeIfAbsent("lazy", k -> "computed");

      assertThat(events).hasSize(1);
      assertThat(events.get(0).oldValue()).isNull();
      assertThat(events.get(0).newValue()).isEqualTo("computed");
    }

    @Test
    @DisplayName("does not fire when key already present")
    void doesNotFireWhenPresent() {
      ctx.set("lazy", "existing");

      List<ContextChangeEvent> events = new CopyOnWriteArrayList<>();
      ctx.onChange("lazy", events::add);

      ctx.computeIfAbsent("lazy", k -> "ignored");

      assertThat(events).isEmpty();
    }
  }

  // ── putIfAbsent() ─────────────────────────────────────────────────────────

  @Nested
  @DisplayName("putIfAbsent()")
  class PutIfAbsent {

    @Test
    @DisplayName("fires when key absent")
    void firesWhenAbsent() {
      List<ContextChangeEvent> events = new CopyOnWriteArrayList<>();
      ctx.onChange("new", events::add);

      ctx.putIfAbsent("new", "value");

      assertThat(events).hasSize(1);
      assertThat(events.get(0).oldValue()).isNull();
      assertThat(events.get(0).newValue()).isEqualTo("value");
    }

    @Test
    @DisplayName("does not fire when key present")
    void doesNotFireWhenPresent() {
      ctx.set("existing", "original");

      List<ContextChangeEvent> events = new CopyOnWriteArrayList<>();
      ctx.onChange("existing", events::add);

      ctx.putIfAbsent("existing", "ignored");

      assertThat(events).isEmpty();
    }
  }

  // ── compareAndSet() ────────────────────────────────────────────────────────

  @Nested
  @DisplayName("compareAndSet()")
  class CompareAndSet {

    @Test
    @DisplayName("fires when CAS succeeds and value changes")
    void firesOnSuccessfulSwap() {
      ctx.set("flag", "off");

      List<ContextChangeEvent> events = new CopyOnWriteArrayList<>();
      ctx.onChange("flag", events::add);

      boolean swapped = ctx.compareAndSet("flag", "off", "on");

      assertThat(swapped).isTrue();
      assertThat(events).hasSize(1);
      assertThat(events.get(0).oldValue()).isEqualTo("off");
      assertThat(events.get(0).newValue()).isEqualTo("on");
    }

    @Test
    @DisplayName("does not fire when CAS fails")
    void doesNotFireOnFailedSwap() {
      ctx.set("flag", "off");

      List<ContextChangeEvent> events = new CopyOnWriteArrayList<>();
      ctx.onChange("flag", events::add);

      boolean swapped = ctx.compareAndSet("flag", "wrong", "on");

      assertThat(swapped).isFalse();
      assertThat(events).isEmpty();
    }
  }

  // ── setPath() ──────────────────────────────────────────────────────────────

  @Nested
  @DisplayName("setPath()")
  class SetPath {

    @Test
    @DisplayName("fires for top-level key on nested path set")
    void firesForTopLevelKey() {
      List<ContextChangeEvent> events = new CopyOnWriteArrayList<>();
      ctx.onChange("data", events::add);

      ctx.setPath("data.nested", "value");

      assertThat(events).hasSize(1);
      assertThat(events.get(0).key()).isEqualTo("data");
    }

    @Test
    @DisplayName("fires for simple (non-nested) path")
    void firesForSimplePath() {
      List<ContextChangeEvent> events = new CopyOnWriteArrayList<>();
      ctx.onChange("simple", events::add);

      ctx.setPath("simple", "value");

      assertThat(events).hasSize(1);
      assertThat(events.get(0).key()).isEqualTo("simple");
      assertThat(events.get(0).oldValue()).isNull();
      assertThat(events.get(0).newValue()).isEqualTo("value");
    }
  }

  // ── Subscription cancellation ──────────────────────────────────────────────

  @Nested
  @DisplayName("Subscription.cancel()")
  class SubscriptionCancel {

    @Test
    @DisplayName("cancel() removes per-key listener")
    void cancelRemovesPerKeyListener() {
      List<ContextChangeEvent> events = new CopyOnWriteArrayList<>();
      Subscription sub = ctx.onChange("key", events::add);

      ctx.set("key", "first");
      assertThat(events).hasSize(1);

      sub.cancel();
      ctx.set("key", "second");

      assertThat(events).hasSize(1); // no second event
    }

    @Test
    @DisplayName("cancel() removes any-change listener")
    void cancelRemovesAnyChangeListener() {
      List<ContextChangeEvent> events = new CopyOnWriteArrayList<>();
      Subscription sub = ctx.onAnyChange(events::add);

      ctx.set("key", "first");
      assertThat(events).hasSize(1);

      sub.cancel();
      ctx.set("key", "second");

      assertThat(events).hasSize(1);
    }
  }

  // ── Error isolation ────────────────────────────────────────────────────────

  @Nested
  @DisplayName("error isolation")
  class ErrorIsolation {

    @Test
    @DisplayName("throwing listener does not prevent subsequent listeners")
    void throwingListenerDoesNotPreventSubsequent() {
      AtomicInteger successCount = new AtomicInteger();

      ctx.onChange("key", e -> successCount.incrementAndGet());
      ctx.onChange(
          "key",
          e -> {
            throw new RuntimeException("boom");
          });
      ctx.onChange("key", e -> successCount.incrementAndGet());

      ctx.set("key", "value");

      assertThat(successCount.get()).isEqualTo(2);
    }

    @Test
    @DisplayName("throwing per-key listener does not prevent any-change listeners")
    void throwingPerKeyDoesNotPreventAnyChange() {
      AtomicInteger anyChangeCount = new AtomicInteger();

      ctx.onChange(
          "key",
          e -> {
            throw new RuntimeException("per-key boom");
          });
      ctx.onAnyChange(e -> anyChangeCount.incrementAndGet());

      ctx.set("key", "value");

      assertThat(anyChangeCount.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("throwing any-change listener does not prevent subsequent any-change listeners")
    void throwingAnyChangeDoesNotPreventSubsequent() {
      AtomicInteger successCount = new AtomicInteger();

      ctx.onAnyChange(e -> successCount.incrementAndGet());
      ctx.onAnyChange(
          e -> {
            throw new RuntimeException("boom");
          });
      ctx.onAnyChange(e -> successCount.incrementAndGet());

      ctx.set("key", "value");

      assertThat(successCount.get()).isEqualTo(2);
    }
  }

  // ── No deadlock from re-entrant writes ─────────────────────────────────────

  @Nested
  @DisplayName("re-entrant writes")
  class ReentrantWrites {

    @Test
    @DisplayName("listener can write to context without deadlock")
    void listenerCanWriteWithoutDeadlock() {
      List<ContextChangeEvent> derivedEvents = new CopyOnWriteArrayList<>();

      // When "trigger" changes, write "derived" in the listener
      ctx.onChange("trigger", e -> ctx.set("derived", "computed-" + e.newValue()));
      ctx.onChange("derived", derivedEvents::add);

      ctx.set("trigger", "go");

      assertThat(ctx.get("derived")).isEqualTo("computed-go");
      assertThat(derivedEvents).hasSize(1);
      assertThat(derivedEvents.get(0).newValue()).isEqualTo("computed-go");
    }
  }

  // ── engineSet() bypass ─────────────────────────────────────────────────────

  @Nested
  @DisplayName("engineSet() bypass")
  class EngineSetBypass {

    @Test
    @DisplayName("engineSet() does NOT fire listeners")
    void engineSetDoesNotFireListeners() {
      List<ContextChangeEvent> events = new CopyOnWriteArrayList<>();
      ctx.onChange("internal", events::add);
      ctx.onAnyChange(events::add);

      // Write directly to the working layer via engineSet (bypasses CaseContextImpl)
      ctx.writableLayer("working").engineSet("internal", "engine-value");

      assertThat(events).isEmpty();
      // But the value is there
      assertThat(ctx.get("internal")).isEqualTo("engine-value");
    }
  }

  // ── applyDiff() bypass ─────────────────────────────────────────────────────

  @Nested
  @DisplayName("applyDiff() bypass")
  class ApplyDiffBypass {

    @Test
    @DisplayName("applyDiff() does NOT fire listeners")
    void applyDiffDoesNotFireListeners() {
      ctx.set("before", "original");

      List<ContextChangeEvent> events = new CopyOnWriteArrayList<>();
      ctx.onAnyChange(events::add);

      // Create a diff that changes "before" and adds "added"
      CaseContextImpl target = new CaseContextImpl();
      target.set("before", "changed");
      target.set("added", "new");

      var diff = ctx.diff(target);
      ctx.applyDiff(diff);

      assertThat(events).isEmpty();
    }
  }

  // ── merge() ────────────────────────────────────────────────────────────────

  @Nested
  @DisplayName("merge()")
  class Merge {

    @Test
    @DisplayName("merge fires per-key events for changed keys")
    void mergeFiresPerKeyEvents() {
      ctx.set("existing", "old");

      List<ContextChangeEvent> events = new CopyOnWriteArrayList<>();
      ctx.onAnyChange(events::add);

      CaseContextImpl other = new CaseContextImpl();
      other.set("existing", "merged");
      other.set("newKey", "newVal");

      ctx.merge(other);

      assertThat(events).hasSize(2);
      assertThat(events)
          .extracting(ContextChangeEvent::key)
          .containsExactlyInAnyOrder("existing", "newKey");
    }
  }

  // ── No-listener fast path ──────────────────────────────────────────────────

  @Nested
  @DisplayName("no-listener fast path")
  class NoListenerFastPath {

    @Test
    @DisplayName("operations work correctly without any listeners registered")
    void operationsWorkWithoutListeners() {
      // Exercises the hasListeners() == false fast path
      ctx.set("a", 1);
      ctx.setAll(Map.of("b", 2, "c", 3));
      ctx.update("a", old -> (int) old + 10);
      ctx.computeIfAbsent("d", k -> 4);
      ctx.putIfAbsent("e", 5);
      ctx.compareAndSet("a", 11, 99);
      ctx.setPath("nested.key", "value");
      ctx.remove("c");

      assertThat(ctx.get("a")).isEqualTo(99);
      assertThat(ctx.get("b")).isEqualTo(2);
      assertThat(ctx.contains("c")).isFalse();
      assertThat(ctx.get("d")).isEqualTo(4);
      assertThat(ctx.get("e")).isEqualTo(5);
      assertThat(ctx.getPath("nested.key")).isEqualTo("value");
    }
  }
}
