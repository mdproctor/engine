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
package io.casehub.blackboard.event;

import io.casehub.blackboard.stage.Stage;
import java.util.UUID;

/**
 * Published when a Stage autocompletes or all required items finish. See casehubio/engine#76.
 *
 * <p>{@code instanceIndex} captures the completing instance's index at publish time — use this
 * field instead of {@code stage.getInstanceIndex()} which may have advanced if the stage is
 * repeatable. Refs casehubio/engine#482.
 *
 * <p><strong>Note:</strong> {@code stage} is passed by reference via {@link
 * io.casehub.blackboard.event.BlackboardEventCodecRegistrar.LocalOnlyCodec}. Consumers must not
 * retain this reference — the Stage object is mutable and its state will reflect subsequent
 * lifecycle transitions.
 */
public record StageCompletedEvent(UUID caseId, Stage stage, int instanceIndex) {}
