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

import io.casehub.worker.api.PlannedAction;

/**
 * Classifies a worker's planned action to determine whether it may proceed autonomously or must be
 * gated for human approval.
 *
 * <p>Implementations must be annotated {@link RiskClassifier} and {@code @ApplicationScoped}. The
 * engine chains all registered implementations — the most restrictive result wins. Multiple
 * consumer repos can provide classifiers simultaneously without conflict.
 *
 * <p>The engine chains all registered implementations via {@link ChainedActionRiskClassifier},
 * which applies most-restrictive-wins semantics.
 *
 * <p>If {@code classify} throws, the engine applies a fail-safe {@link RiskDecision.GateRequired}
 * requiring manual review. Do not throw to bypass the gate — the fail-safe will catch it.
 */
public interface ActionRiskClassifier {

  RiskDecision classify(PlannedAction action, ClassificationContext context);
}
