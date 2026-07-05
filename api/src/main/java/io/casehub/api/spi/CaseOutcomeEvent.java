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

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Outcome event fired by the engine when a case reaches a terminal state (COMPLETED, FAULTED, or
 * CANCELLED). Delivered to all {@link CaseOutcomeObserver} beans discovered via CDI.
 *
 * <p>{@code outcomeLabel} reflects the terminal status name ("COMPLETED", "FAULTED", "CANCELLED").
 * Applications that need domain-specific labels (e.g. "WIN", "LOSS") can derive them from {@code
 * caseFileSnapshot} in their observer implementation.
 *
 * <p>{@code caseFileSnapshot} is the working layer context at the time of terminal transition — the
 * last committed view of the case state, including all worker outputs. Treat it as read-only.
 *
 * <p>Refs casehubio/engine#477 (CBR Retain step).
 *
 * @param caseType case definition name (e.g. "aml-investigation", "starcraft-game")
 * @param caseId case instance UUID
 * @param caseFileSnapshot working-layer context at case close; non-null, may be empty
 * @param outcomeLabel terminal status name: "COMPLETED", "FAULTED", or "CANCELLED"
 * @param closedAt timestamp of the terminal transition
 * @param metadata additional context provided by the engine; currently empty, reserved for future
 *     use
 */
public record CaseOutcomeEvent(
    String caseType,
    UUID caseId,
    Map<String, Object> caseFileSnapshot,
    String outcomeLabel,
    Instant closedAt,
    Map<String, Object> metadata) {}
