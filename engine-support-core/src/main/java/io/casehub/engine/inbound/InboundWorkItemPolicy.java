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
package io.casehub.engine.inbound;

import io.casehub.qhorus.api.gateway.MessageReceivedEvent;
import io.casehub.work.api.WorkItemCreateRequest;
import java.util.Optional;

/**
 * SPI — decides whether and how to create a WorkItem from an inbound qhorus message.
 *
 * <p>Deployed by consumer applications. No {@code @DefaultBean} — the bridge is completely inert
 * without a registered policy bean. Exactly one policy bean is expected; ambiguity is a deployment
 * error surfaced at startup. Consumers needing multiple policies compose them in a single
 * {@code @ApplicationScoped} implementation.
 *
 * <p>Contract:
 *
 * <ul>
 *   <li>{@link Optional#empty()} — message silently ignored, no WorkItem created
 *   <li>{@link Optional#of(Object)} — WorkItem created under the event's tenancyId; bridge stamps
 *       {@code createdBy = "casehub-engine-inbound"} unconditionally
 *   <li>Throw — logged at WARN, no WorkItem created, no case impact
 * </ul>
 *
 * <p><strong>Channel filtering:</strong> the bridge delivers every qhorus channel message to {@link
 * #decide} — it does not pre-filter by channel name. Implementations must inspect {@code
 * event.channelName()}, {@code event.messageType()}, or other fields and return {@link
 * java.util.Optional#empty()} for messages they do not handle.
 *
 * <p>Placement: this SPI lives in the bridge module, not {@code casehub-engine-api/spi/}, because
 * its parameter type {@link WorkItemCreateRequest} comes from {@code casehub-work}, which must not
 * become a transitive dependency of {@code casehub-engine-api} consumers (PP-20260601-c43112).
 *
 * <p>Note on {@code callerRef}: if set to {@code case:{caseId}/pi:{planItemId}} format and {@code
 * casehub-work-engine-adapter} is deployed, the WorkItem lifecycle will be wired back to that
 * PlanItem. Bridge-created WorkItems with no case backing should leave {@code callerRef} null.
 */
@FunctionalInterface
public interface InboundWorkItemPolicy {

  Optional<WorkItemCreateRequest> decide(MessageReceivedEvent event);
}
