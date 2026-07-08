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
package io.casehub.work.engine;

import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Sealed hierarchy for the {@code callerRef} string embedded by CaseHub when spawning a
 * quarkus-work WorkItem child.
 *
 * <p>Two concrete types:
 *
 * <ul>
 *   <li>{@link PlanItemCallerRef} — {@code "case:{caseId}/pi:{planItemId}"} — standard WorkItem
 *       created from a humanTask YAML binding. Routes back to a blackboard PlanItem.
 *   <li>{@link GateCallerRef} — {@code "case:{caseId}/gate:{gateId}"} — WorkItem created for an
 *       action gate. Routes to {@code ActionGateCompletionApplier}; bypasses the blackboard guard
 *       in {@code WorkItemLifecycleAdapter}.
 * </ul>
 *
 * <p>CaseHub owns the semantics of these opaque strings — quarkus-work stores them unchanged on the
 * WorkItem and echoes them back in every WorkItemLifecycleEvent. Refs casehubio/work#136.
 */
public sealed interface CallerRef permits PlanItemCallerRef, GateCallerRef {

  UUID caseId();

  /**
   * Parses a callerRef string, returning {@code null} if the string is not a recognised CaseHub
   * callerRef format.
   */
  static CallerRef parse(final String raw) {
    if (raw == null) return null;
    final Matcher pi = Patterns.PI.matcher(raw);
    if (pi.matches()) {
      try {
        return new PlanItemCallerRef(UUID.fromString(pi.group(1)), pi.group(2));
      } catch (IllegalArgumentException e) {
        return null;
      }
    }
    final Matcher gate = Patterns.GATE.matcher(raw);
    if (gate.matches()) {
      try {
        return new GateCallerRef(UUID.fromString(gate.group(1)), Long.parseLong(gate.group(2)));
      } catch (IllegalArgumentException e) {
        return null; // covers both UUID parse and number parse errors
      }
    }
    return null;
  }

  /** Compiled patterns kept here to avoid re-compilation on each parse() call. */
  final class Patterns {
    static final Pattern PI = Pattern.compile("^case:([0-9a-fA-F-]{36})/pi:(.+)$");
    static final Pattern GATE = Pattern.compile("^case:([0-9a-fA-F-]{36})/gate:(\\d+)$");

    private Patterns() {}
  }
}
