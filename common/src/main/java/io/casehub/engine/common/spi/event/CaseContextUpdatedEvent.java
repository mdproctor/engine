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
package io.casehub.engine.common.spi.event;

import java.util.UUID;

/**
 * CDI event fired when a case's context is updated via a layer mutation. Published from
 * {@code CaseContextChangedEventHandler} as the SPI-layer CDI complement to the internal
 * Vert.x {@code CaseContextChangedEvent}.
 *
 * <p>Only fired when {@code changedLayer} is non-null — initial case start signals
 * (where no specific layer was mutated) do not produce this event.
 *
 * @param caseId the case whose context was updated
 * @param changedLayer the name of the context layer that was mutated
 * @param tenancyId the tenant that owns the case
 */
public record CaseContextUpdatedEvent(
    UUID caseId,
    String changedLayer,
    String tenancyId) {}
