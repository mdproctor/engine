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
import io.smallrye.mutiny.Uni;

/**
 * Reactive variant of {@link ActionRiskClassifier} — the primary interface called by the engine.
 *
 * <p>The engine's {@code WorkflowExecutionCompletedHandler} injects and calls this interface. The
 * engine ships {@code ChainedReactiveActionRiskClassifier} as the sole non-default implementation;
 * it discovers all {@link RiskClassifier}-qualified {@link ActionRiskClassifier} beans and chains
 * them.
 *
 * <p>Consumer implementations should implement the blocking {@link ActionRiskClassifier} with the
 * {@link RiskClassifier} qualifier rather than this interface directly. The chain bridges blocking
 * classifiers to reactive and offloads them to the worker thread pool to avoid blocking the Vert.x
 * IO thread.
 */
public interface ReactiveActionRiskClassifier {

  Uni<RiskDecision> classify(PlannedAction action, ClassificationContext context);
}
