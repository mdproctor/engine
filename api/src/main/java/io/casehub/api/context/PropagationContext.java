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

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Immutable tracing and budget context that flows from parent to child across a case hierarchy.
 *
 * <p>Carries a W3C-compatible trace ID (shared across the entire hierarchy), inherited attributes
 * (e.g. tenantId, userId), and an optional resource budget expressed as a deadline and remaining
 * duration. Child contexts inherit the parent's trace ID and reduce the remaining budget by the
 * elapsed time since the parent was created.
 *
 * <p>Use {@link #createRoot()} or its overloads to start a new trace, and {@link #createChild()} /
 * {@link #createChild(Map)} to propagate context through nested work. Use {@link
 * #fromStorage(String, Map, Instant, Duration)} to restore a context from persistence.
 *
 * @see <a href="https://github.com/casehubio/engine/issues/117">casehubio/engine#117</a>
 */
public class PropagationContext {

  private final String traceId;
  private final Map<String, String> inheritedAttributes;
  private final Instant deadline; // null = no deadline
  private final Duration remainingBudget; // null = no budget
  private final Instant createdAt; // used for child budget calculation only

  private PropagationContext(
      String traceId,
      Map<String, String> inheritedAttributes,
      Instant deadline,
      Duration remainingBudget) {
    this.traceId = traceId;
    this.inheritedAttributes = Map.copyOf(inheritedAttributes);
    this.deadline = deadline;
    this.remainingBudget = remainingBudget;
    this.createdAt = Instant.now();
  }

  /**
   * Creates a root context with a new random W3C-compatible trace ID, no inherited attributes, and
   * no budget.
   */
  public static PropagationContext createRoot() {
    return new PropagationContext(UUID.randomUUID().toString(), Map.of(), null, null);
  }

  /**
   * Creates a root context with the given inherited attributes and no budget.
   *
   * @param attributes key-value pairs to carry through the hierarchy (e.g. tenantId, userId)
   */
  public static PropagationContext createRoot(Map<String, String> attributes) {
    return new PropagationContext(UUID.randomUUID().toString(), attributes, null, null);
  }

  /**
   * Creates a root context with inherited attributes and a time budget. The deadline is computed as
   * {@code now + budget}.
   *
   * @param attributes key-value pairs to carry through the hierarchy
   * @param budget maximum duration allowed for work under this context
   */
  public static PropagationContext createRoot(Map<String, String> attributes, Duration budget) {
    Instant deadline = Instant.now().plus(budget);
    return new PropagationContext(UUID.randomUUID().toString(), attributes, deadline, budget);
  }

  /**
   * Creates a root context with a caller-supplied trace ID, no inherited attributes, and no budget.
   * Use when the caller can supply the active OTel trace ID so that case spans are correlatable in
   * distributed tracing systems.
   *
   * @param traceId the trace ID to use (must not be null)
   */
  public static PropagationContext createRoot(String traceId) {
    Objects.requireNonNull(traceId, "traceId must not be null");
    return new PropagationContext(traceId, Map.of(), null, null);
  }

  /**
   * Creates a root context with a caller-supplied trace ID and inherited attributes, but no budget.
   *
   * @param traceId the trace ID to use (must not be null)
   * @param attributes key-value pairs to carry through the hierarchy (e.g. userId, roles)
   */
  public static PropagationContext createRoot(String traceId, Map<String, String> attributes) {
    Objects.requireNonNull(traceId, "traceId must not be null");
    return new PropagationContext(traceId, attributes, null, null);
  }

  /**
   * Creates a root context with a caller-supplied trace ID, inherited attributes, and a time
   * budget. The deadline is computed as {@code now + budget}.
   *
   * @param traceId the trace ID to use (must not be null)
   * @param attributes key-value pairs to carry through the hierarchy
   * @param budget maximum duration allowed for work under this context
   */
  public static PropagationContext createRoot(
      String traceId, Map<String, String> attributes, Duration budget) {
    Objects.requireNonNull(traceId, "traceId must not be null");
    Instant deadline = Instant.now().plus(budget);
    return new PropagationContext(traceId, attributes, deadline, budget);
  }

  /**
   * Reconstructs a {@link PropagationContext} from persistence. Used by storage providers to
   * restore context from entity fields.
   *
   * @param traceId the trace ID to restore (must not be null)
   * @param attributes the inherited attributes, or null to use an empty map
   * @param deadline the deadline instant, or null if none was set
   * @param remainingBudget the remaining budget duration, or null if none was set
   */
  public static PropagationContext fromStorage(
      String traceId, Map<String, String> attributes, Instant deadline, Duration remainingBudget) {
    Objects.requireNonNull(traceId, "traceId must not be null");
    return new PropagationContext(
        traceId, attributes != null ? attributes : Map.of(), deadline, remainingBudget);
  }

  /**
   * Creates a child context that inherits this context's trace ID and attributes. If this context
   * has a remaining budget, the child's budget is reduced by the elapsed time since this context
   * was created. The child budget is clamped to {@link Duration#ZERO} and never goes negative.
   */
  public PropagationContext createChild() {
    return createChild(Map.of());
  }

  /**
   * Creates a child context that inherits this context's trace ID and merges additional attributes.
   * Child attributes override parent attributes on key collision. If this context has a remaining
   * budget, the child's budget is reduced by the elapsed time since this context was created.
   *
   * @param additionalAttributes extra attributes to add or override in the child
   */
  public PropagationContext createChild(Map<String, String> additionalAttributes) {
    Map<String, String> childAttrs = new HashMap<>(this.inheritedAttributes);
    childAttrs.putAll(additionalAttributes);

    Duration childBudget = null;
    Instant childDeadline = null;
    if (this.remainingBudget != null) {
      Duration elapsed = Duration.between(this.createdAt, Instant.now());
      Duration remaining = this.remainingBudget.minus(elapsed);
      childBudget = remaining.isNegative() ? Duration.ZERO : remaining;
      childDeadline = childBudget.isZero() ? Instant.now() : Instant.now().plus(childBudget);
    }

    return new PropagationContext(this.traceId, childAttrs, childDeadline, childBudget);
  }

  /**
   * Returns {@code true} if the budget for this context is exhausted — either the deadline has
   * passed or the remaining budget duration is zero or negative. Returns {@code false} when no
   * deadline or budget was set.
   */
  public boolean isBudgetExhausted() {
    if (deadline != null && Instant.now().isAfter(deadline)) return true;
    return remainingBudget != null && (remainingBudget.isZero() || remainingBudget.isNegative());
  }

  /**
   * Returns the value for the given attribute key, or {@link Optional#empty()} if not present.
   *
   * @param key the attribute key to look up
   */
  public Optional<String> getAttribute(String key) {
    return Optional.ofNullable(inheritedAttributes.get(key));
  }

  /** Returns the W3C-compatible trace ID shared across the entire parent-child hierarchy. */
  public String getTraceId() {
    return traceId;
  }

  /** Returns an unmodifiable view of the inherited attributes map. */
  public Map<String, String> getInheritedAttributes() {
    return inheritedAttributes;
  }

  /** Returns the deadline instant, or {@link Optional#empty()} if no deadline was set. */
  public Optional<Instant> getDeadline() {
    return Optional.ofNullable(deadline);
  }

  /**
   * Returns the remaining budget duration, or {@link Optional#empty()} if no budget was set. Note:
   * this value reflects the budget at the time the context was created (or passed from parent), not
   * a live countdown.
   */
  public Optional<Duration> getRemainingBudget() {
    return Optional.ofNullable(remainingBudget);
  }
}
