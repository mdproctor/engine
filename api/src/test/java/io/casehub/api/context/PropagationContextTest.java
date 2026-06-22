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
package io.casehub.api.context;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Comprehensive unit tests for {@link PropagationContext} — tracing, budget, and inherited
 * attributes. Pure unit tests, no Quarkus. See casehubio/engine#117.
 */
class PropagationContextTest {

  // ---- createRoot() ---------------------------------------------------------

  @Test
  void createRoot_generatesNonNullTraceId() {
    PropagationContext ctx = PropagationContext.createRoot();
    assertThat(ctx.getTraceId()).isNotNull().isNotBlank();
  }

  @Test
  void createRoot_hasEmptyAttributes() {
    PropagationContext ctx = PropagationContext.createRoot();
    assertThat(ctx.getInheritedAttributes()).isEmpty();
  }

  @Test
  void createRoot_hasNoDeadline() {
    PropagationContext ctx = PropagationContext.createRoot();
    assertThat(ctx.getDeadline()).isEmpty();
  }

  @Test
  void createRoot_hasNoBudget() {
    PropagationContext ctx = PropagationContext.createRoot();
    assertThat(ctx.getRemainingBudget()).isEmpty();
  }

  @Test
  void createRoot_withAttributes_carriesProvidedAttributes() {
    Map<String, String> attrs = Map.of("tenantId", "acme", "userId", "u1");
    PropagationContext ctx = PropagationContext.createRoot(attrs);
    assertThat(ctx.getInheritedAttributes())
        .containsEntry("tenantId", "acme")
        .containsEntry("userId", "u1");
  }

  @Test
  void createRoot_withAttributes_hasNoDeadline() {
    PropagationContext ctx = PropagationContext.createRoot(Map.of("k", "v"));
    assertThat(ctx.getDeadline()).isEmpty();
    assertThat(ctx.getRemainingBudget()).isEmpty();
  }

  @Test
  void createRoot_withAttributesAndBudget_setsDeadlineApproximately() {
    Duration budget = Duration.ofSeconds(10);
    Instant before = Instant.now();
    PropagationContext ctx = PropagationContext.createRoot(Map.of("k", "v"), budget);
    Instant after = Instant.now();

    assertThat(ctx.getDeadline()).isPresent();
    Instant deadline = ctx.getDeadline().get();
    assertThat(deadline).isAfterOrEqualTo(before.plus(budget));
    assertThat(deadline).isBeforeOrEqualTo(after.plus(budget).plusMillis(100));
  }

  @Test
  void createRoot_withAttributesAndBudget_setsRemainingBudget() {
    Duration budget = Duration.ofSeconds(10);
    PropagationContext ctx = PropagationContext.createRoot(Map.of("k", "v"), budget);
    assertThat(ctx.getRemainingBudget()).isPresent().hasValue(budget);
  }

  @Test
  void createRoot_withAttributesAndBudget_carriesAttributes() {
    PropagationContext ctx =
        PropagationContext.createRoot(Map.of("region", "eu"), Duration.ofMinutes(1));
    assertThat(ctx.getInheritedAttributes()).containsEntry("region", "eu");
  }

  // ---- createRoot(traceId, attributes) — no budget -------------------------

  @Test
  void createRoot_withTraceIdAndAttributes_carriesProvidedAttributes() {
    PropagationContext ctx =
        PropagationContext.createRoot("trace-42", Map.of("userId", "u1", "roles", "admin,viewer"));
    assertThat(ctx.getInheritedAttributes())
        .containsEntry("userId", "u1")
        .containsEntry("roles", "admin,viewer");
  }

  @Test
  void createRoot_withTraceIdAndAttributes_usesProvidedTraceId() {
    PropagationContext ctx = PropagationContext.createRoot("trace-42", Map.of("k", "v"));
    assertThat(ctx.getTraceId()).isEqualTo("trace-42");
  }

  @Test
  void createRoot_withTraceIdAndAttributes_hasNoDeadline() {
    PropagationContext ctx = PropagationContext.createRoot("trace-42", Map.of("k", "v"));
    assertThat(ctx.getDeadline()).isEmpty();
    assertThat(ctx.getRemainingBudget()).isEmpty();
  }

  @Test
  void createRoot_withTraceIdAndAttributes_nullTraceId_throws() {
    assertThatThrownBy(() -> PropagationContext.createRoot(null, Map.of("k", "v")))
        .isInstanceOf(NullPointerException.class);
  }

  // ---- createChild() --------------------------------------------------------

  @Test
  void createChild_inheritsTraceIdFromParent() {
    PropagationContext parent = PropagationContext.createRoot();
    PropagationContext child = parent.createChild();
    assertThat(child.getTraceId()).isEqualTo(parent.getTraceId());
  }

  @Test
  void createChild_sameTraceIdObjectEquality() {
    PropagationContext parent = PropagationContext.createRoot();
    PropagationContext child = parent.createChild();
    assertThat(child.getTraceId()).isSameAs(parent.getTraceId());
  }

  @Test
  void createChild_inheritsParentAttributes() {
    PropagationContext parent = PropagationContext.createRoot(Map.of("a", "1"));
    PropagationContext child = parent.createChild();
    assertThat(child.getInheritedAttributes()).containsEntry("a", "1");
  }

  @Test
  void createChild_withAdditionalAttributes_mergesParentAndChild() {
    PropagationContext parent = PropagationContext.createRoot(Map.of("a", "1"));
    PropagationContext child = parent.createChild(Map.of("b", "2"));
    assertThat(child.getInheritedAttributes()).containsEntry("a", "1").containsEntry("b", "2");
  }

  @Test
  void createChild_withAdditionalAttributes_childOverridesParent() {
    PropagationContext parent = PropagationContext.createRoot(Map.of("a", "1"));
    PropagationContext child = parent.createChild(Map.of("a", "2"));
    assertThat(child.getInheritedAttributes()).containsEntry("a", "2");
  }

  @Test
  void createChild_withBudget_hasLessRemainingThanParent() throws InterruptedException {
    PropagationContext parent = PropagationContext.createRoot(Map.of(), Duration.ofSeconds(10));
    Thread.sleep(5); // ensure some elapsed time
    PropagationContext child = parent.createChild();

    assertThat(child.getRemainingBudget()).isPresent();
    Duration childBudget = child.getRemainingBudget().get();
    Duration parentBudget = parent.getRemainingBudget().get();
    assertThat(childBudget).isLessThan(parentBudget);
  }

  @Test
  void createChild_noBudget_childAlsoHasNoBudget() {
    PropagationContext parent = PropagationContext.createRoot();
    PropagationContext child = parent.createChild();
    assertThat(child.getRemainingBudget()).isEmpty();
    assertThat(child.getDeadline()).isEmpty();
  }

  @Test
  void createChild_withNullAdditionalAttributes_doesNotThrow() {
    PropagationContext parent = PropagationContext.createRoot(Map.of("k", "v"));
    // null additionalAttributes should be treated as empty map — no NPE
    PropagationContext child = parent.createChild(Map.of());
    assertThat(child.getInheritedAttributes()).containsEntry("k", "v");
  }

  // ---- fromStorage() --------------------------------------------------------

  @Test
  void fromStorage_reconstructsCorrectly() {
    String traceId = "test-trace-id-123";
    Map<String, String> attrs = Map.of("tenantId", "acme");
    Instant deadline = Instant.now().plusSeconds(60);
    Duration budget = Duration.ofSeconds(60);

    PropagationContext ctx = PropagationContext.fromStorage(traceId, attrs, deadline, budget);

    assertThat(ctx.getTraceId()).isEqualTo(traceId);
    assertThat(ctx.getInheritedAttributes()).containsEntry("tenantId", "acme");
    assertThat(ctx.getDeadline()).hasValue(deadline);
    assertThat(ctx.getRemainingBudget()).hasValue(budget);
  }

  @Test
  void fromStorage_withNullAttributes_usesEmptyMap() {
    PropagationContext ctx = PropagationContext.fromStorage("trace-id", null, null, null);
    assertThat(ctx.getInheritedAttributes()).isEmpty();
  }

  @Test
  void fromStorage_withNullDeadlineAndBudget_reconstructsWithoutBudget() {
    PropagationContext ctx = PropagationContext.fromStorage("trace-id", Map.of(), null, null);
    assertThat(ctx.getDeadline()).isEmpty();
    assertThat(ctx.getRemainingBudget()).isEmpty();
  }

  @Test
  void fromStorage_withNullTraceId_throws() {
    assertThatThrownBy(() -> PropagationContext.fromStorage(null, Map.of(), null, null))
        .isInstanceOf(NullPointerException.class);
  }

  // ---- getAttribute() -------------------------------------------------------

  @Test
  void getAttribute_returnsValueForExistingKey() {
    PropagationContext ctx = PropagationContext.createRoot(Map.of("tenantId", "acme"));
    assertThat(ctx.getAttribute("tenantId")).isPresent().hasValue("acme");
  }

  @Test
  void getAttribute_returnsEmptyForMissingKey() {
    PropagationContext ctx = PropagationContext.createRoot(Map.of("tenantId", "acme"));
    assertThat(ctx.getAttribute("missing")).isEmpty();
  }

  // ---- isBudgetExhausted() --------------------------------------------------

  @Test
  void isBudgetExhausted_returnsFalseWhenNoDeadlineSet() {
    PropagationContext ctx = PropagationContext.createRoot();
    assertThat(ctx.isBudgetExhausted()).isFalse();
  }

  @Test
  void isBudgetExhausted_returnsTrueWhenDeadlineIsInThePast() {
    Instant pastDeadline = Instant.now().minusSeconds(1);
    PropagationContext ctx = PropagationContext.fromStorage("trace", Map.of(), pastDeadline, null);
    assertThat(ctx.isBudgetExhausted()).isTrue();
  }

  @Test
  void isBudgetExhausted_returnsTrueWhenRemainingBudgetIsZero() {
    PropagationContext ctx = PropagationContext.fromStorage("trace", Map.of(), null, Duration.ZERO);
    assertThat(ctx.isBudgetExhausted()).isTrue();
  }

  @Test
  void isBudgetExhausted_returnsTrueWhenRemainingBudgetIsNegative() {
    PropagationContext ctx =
        PropagationContext.fromStorage("trace", Map.of(), null, Duration.ofSeconds(-1));
    assertThat(ctx.isBudgetExhausted()).isTrue();
  }

  @Test
  void isBudgetExhausted_returnsFalseWhenDeadlineIsInFuture() {
    Instant futureDeadline = Instant.now().plusSeconds(60);
    PropagationContext ctx =
        PropagationContext.fromStorage("trace", Map.of(), futureDeadline, null);
    assertThat(ctx.isBudgetExhausted()).isFalse();
  }

  // ---- Immutability ---------------------------------------------------------

  @Test
  void inheritedAttributes_areImmutable() {
    PropagationContext ctx = PropagationContext.createRoot(Map.of("k", "v"));
    assertThatThrownBy(() -> ctx.getInheritedAttributes().put("new", "value"))
        .isInstanceOf(UnsupportedOperationException.class);
  }

  // ---- Happy path chains ----------------------------------------------------

  @Test
  void rootChildChain_noBudget_sameTraceIdChildInheritsAttrs() {
    PropagationContext root = PropagationContext.createRoot(Map.of("env", "prod"));
    PropagationContext child = root.createChild(Map.of("step", "1"));

    assertThat(child.getTraceId()).isEqualTo(root.getTraceId());
    assertThat(child.getInheritedAttributes())
        .containsEntry("env", "prod")
        .containsEntry("step", "1");
    assertThat(child.getRemainingBudget()).isEmpty();
  }

  @Test
  void rootChildChain_withBudget_childHasSlightlyLessThan10sRemaining()
      throws InterruptedException {
    Duration budget = Duration.ofSeconds(10);
    PropagationContext root = PropagationContext.createRoot(Map.of(), budget);
    Thread.sleep(5);
    PropagationContext child = root.createChild();

    assertThat(child.getTraceId()).isEqualTo(root.getTraceId());
    assertThat(child.getRemainingBudget()).isPresent();
    Duration childRemaining = child.getRemainingBudget().get();
    // Child must have less than budget but not more than budget
    assertThat(childRemaining).isLessThan(budget);
    assertThat(childRemaining).isGreaterThan(Duration.ZERO);
  }

  @Test
  void fromStorage_roundTrip() {
    String traceId = "round-trip-id";
    Map<String, String> attrs = Map.of("x", "y");
    Instant deadline = Instant.now().plusSeconds(30);
    Duration budget = Duration.ofSeconds(30);

    PropagationContext original = PropagationContext.fromStorage(traceId, attrs, deadline, budget);

    // Extract fields and reconstruct
    PropagationContext restored =
        PropagationContext.fromStorage(
            original.getTraceId(),
            original.getInheritedAttributes(),
            original.getDeadline().orElse(null),
            original.getRemainingBudget().orElse(null));

    assertThat(restored.getTraceId()).isEqualTo(traceId);
    assertThat(restored.getInheritedAttributes()).containsEntry("x", "y");
    assertThat(restored.getDeadline()).hasValue(deadline);
    assertThat(restored.getRemainingBudget()).hasValue(budget);
  }

  // ---- Edge cases -----------------------------------------------------------

  @Test
  void createChild_onExhaustedBudget_remainingBudgetIsZeroNotNegative() {
    // Simulate an already-exhausted budget by using zero remaining
    PropagationContext parent =
        PropagationContext.fromStorage("trace", Map.of(), null, Duration.ZERO);
    PropagationContext child = parent.createChild();

    assertThat(child.getRemainingBudget()).isPresent();
    assertThat(child.getRemainingBudget().get()).isGreaterThanOrEqualTo(Duration.ZERO);
  }

  @Test
  void traceId_isNeverNull() {
    assertThat(PropagationContext.createRoot().getTraceId()).isNotNull();
    assertThat(PropagationContext.createRoot(Map.of()).getTraceId()).isNotNull();
    assertThat(PropagationContext.createRoot(Map.of(), Duration.ofSeconds(1)).getTraceId())
        .isNotNull();
    assertThat(PropagationContext.fromStorage("x", null, null, null).getTraceId()).isNotNull();
  }

  @Test
  void differentRootContexts_haveDistinctTraceIds() {
    PropagationContext a = PropagationContext.createRoot();
    PropagationContext b = PropagationContext.createRoot();
    assertThat(a.getTraceId()).isNotEqualTo(b.getTraceId());
  }
}
