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

import io.casehub.qhorus.api.gateway.MessageObserver;
import io.casehub.qhorus.api.gateway.MessageReceivedEvent;
import io.casehub.work.api.WorkItemCreateRequest;
import io.casehub.work.api.spi.TenantContextExecutor;
import io.casehub.work.api.spi.WorkItemOperations;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import java.util.Optional;
import org.jboss.logging.Logger;

/**
 * Bridges inbound qhorus messages to casehub-work WorkItems.
 *
 * <p>Registered as a {@link MessageObserver} — {@code MessageObserverDispatcher} calls {@link
 * #onMessage} synchronously for every message received on any qhorus channel. The bridge delegates
 * to {@link InboundWorkItemPolicy} to decide whether and how to create a WorkItem.
 *
 * <p>Inert with no policy bean present. Fail-fast on ambiguous policy at startup.
 *
 * <p><strong>Channel routing:</strong> this bridge overrides no {@code channels()} filter — {@code
 * MessageObserver.channels()} returns {@code Set.of()} by default, which {@code
 * MessageObserverDispatcher} treats as "all channels". Every message on every qhorus channel is
 * delivered to {@link #onMessage}. Policies must self-filter by inspecting {@code
 * event.channelName()}, {@code event.messageType()}, or other fields and returning {@code
 * Optional.empty()} for messages they do not handle.
 *
 * <p><strong>Exception handling:</strong> policy exceptions are caught and logged with channel
 * context; the bridge returns without creating a WorkItem. Infrastructure exceptions ({@code
 * TenantContextExecutor}, {@code WorkItemOperations}) propagate out of {@link #onMessage} to {@code
 * MessageObserverDispatcher}'s outer safety net, which catches and logs them at WARN — neither path
 * retries or produces a hard failure to the message sender.
 *
 * <p><strong>Request context:</strong> {@code TenantContextExecutor.runInTenantContext()} activates
 * a CDI request context if none is active (normal case in qhorus {@code afterCompletion}
 * callbacks), sets tenant identity, runs the work, then terminates the context. {@code
 * WorkItemOperations.create()} manages its own transaction boundary independently of the request
 * context lifecycle.
 *
 * <p><strong>At-most-once delivery:</strong> {@code onMessage} runs in the qhorus JTA {@code
 * afterCompletion(STATUS_COMMITTED)} callback — the qhorus message is committed before observers
 * fire. If {@code WorkItemOperations.create()} fails, no WorkItem is created and no retry occurs.
 */
@ApplicationScoped
public class InboundWorkItemBridge implements MessageObserver {

  private static final Logger LOG = Logger.getLogger(InboundWorkItemBridge.class);
  private static final String CREATED_BY = "casehub-engine-inbound";

  @Inject Instance<InboundWorkItemPolicy> policy;
  @Inject WorkItemOperations workItemOperations;
  @Inject TenantContextExecutor tenantContextExecutor;

  void onStartup(@Observes final StartupEvent ignored) {
    if (policy.isAmbiguous()) {
      throw new IllegalStateException(
          "Multiple InboundWorkItemPolicy beans found — compose them in a single"
              + " @ApplicationScoped implementation");
    }
  }

  @Override
  public void onMessage(final MessageReceivedEvent event) {
    if (policy.isUnsatisfied()) {
      return;
    }

    final Optional<WorkItemCreateRequest> decision;
    try {
      decision = policy.get().decide(event);
    } catch (Exception e) {
      LOG.warnf(
          e,
          "InboundWorkItemPolicy.decide() threw for channel %s — message ignored",
          event.channelName());
      return;
    }

    decision.ifPresent(
        request ->
            tenantContextExecutor.runInTenantContext(
                event.tenancyId(), () -> workItemOperations.create(stamp(request))));
  }

  private WorkItemCreateRequest stamp(final WorkItemCreateRequest request) {
    return request.toBuilder().createdBy(CREATED_BY).build();
  }
}
