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
 * Published by {@code JudgmentEscalationHandler} when the escalation decision is Fault. Consumed by
 * the planning module to mark the PlanItem FAULTED and write diagnostics.
 *
 * <p>Refs engine#1000, engine#999.
 */
public record JudgmentFaultEvent(
    UUID caseId, String tenancyId, String bindingName, String reason) {}
