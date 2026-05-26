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
package io.casehub.engine.common.internal.event;

import java.util.UUID;

/**
 * CDI event fired on every auditable case lifecycle transition.
 *
 * <p>Fired via {@code Event.fireAsync()} from Vert.x event-bus handlers so that optional modules
 * (e.g. casehub-ledger) can observe transitions without the engine depending on them. If no
 * observer is present the event fires into the void.
 *
 * @param caseId the case instance UUID
 * @param commandType the actor intent — e.g. {@code "StartCase"}, {@code "SuspendCase"}
 * @param eventType the observable fact — e.g. {@code "CaseStarted"}, {@code "CaseSuspended"}
 * @param caseStatus snapshot of CaseStatus at transition time; null for non-status events
 * @param actorId the initiating actor; null for system-triggered events
 * @param actorRole the actor's role in this transition; null when not applicable
 */
public record CaseLifecycleEvent(
    UUID caseId,
    String commandType,
    String eventType,
    String caseStatus,
    String actorId,
    String actorRole) {}
