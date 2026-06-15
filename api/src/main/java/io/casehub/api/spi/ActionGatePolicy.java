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
package io.casehub.api.spi;

/**
 * Gate policy for {@link ActionRiskClassifier} implementations. Determines when a worker's {@link
 * PlannedAction} requires human approval before the engine applies the output.
 *
 * <p>Domain classifiers (AML, clinical, devtown, life) reference this enum instead of defining
 * their own equivalent — see casehubio/engine#472.
 *
 * <ul>
 *   <li>{@link #ALWAYS} — every action of this type requires a gate, regardless of score or
 *       context. Use for irreversible or high-stakes actions (e.g. filing a SAR, administering
 *       medication).
 *   <li>{@link #THRESHOLD} — gate when the action's risk score exceeds a configured threshold. The
 *       threshold value is owned by the domain classifier, not by this enum.
 *   <li>{@link #CONDITIONAL} — gate based on contextual evaluation (e.g. JQ expression against the
 *       case context, NLI classification of the action description). The evaluation logic is owned
 *       by the domain classifier.
 * </ul>
 */
public enum ActionGatePolicy {
  ALWAYS,
  THRESHOLD,
  CONDITIONAL
}
