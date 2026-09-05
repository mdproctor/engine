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
package io.casehub.engine.watchdog;

import io.casehub.api.model.CaseChannel;
import io.casehub.api.model.CaseDefinition;
import io.casehub.api.model.StallRecoveryContext;
import io.casehub.api.model.StallRecoveryPolicy;
import io.casehub.engine.common.internal.model.CaseInstance;
import io.casehub.engine.common.spi.CaseDefinitionRegistry;
import io.casehub.engine.common.spi.cache.CaseInstanceCache;
import io.casehub.qhorus.api.watchdog.AgentStaleContext;
import io.casehub.qhorus.api.watchdog.AlertContext;
import io.casehub.qhorus.api.watchdog.ApprovalPendingContext;
import io.casehub.qhorus.api.watchdog.BarrierStuckContext;
import io.casehub.qhorus.api.watchdog.ChannelIdleContext;
import io.casehub.qhorus.api.watchdog.CircularDelegationContext;
import io.casehub.qhorus.api.watchdog.ContextPressureContext;
import io.casehub.qhorus.api.watchdog.ConversationStallContext;
import io.casehub.qhorus.api.watchdog.DeliveryLagContext;
import io.casehub.qhorus.api.watchdog.EchoChamberContext;
import io.casehub.qhorus.api.watchdog.LoopDetectedContext;
import io.casehub.qhorus.api.watchdog.ObligationFanOutContext;
import io.casehub.qhorus.api.watchdog.QueueDepthContext;
import io.casehub.qhorus.api.watchdog.WatchdogAlertEvent;
import io.vertx.mutiny.core.eventbus.EventBus;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.ObservesAsync;
import jakarta.inject.Inject;
import java.util.UUID;
import org.jboss.logging.Logger;

@ApplicationScoped
public class WatchdogAlertObserver {

  static final String STALL_RECOVERY_ADDRESS = "casehub.stall.recovery";

  private static final Logger LOG = Logger.getLogger(WatchdogAlertObserver.class);

  @Inject CaseInstanceCache caseInstanceCache;
  @Inject CaseDefinitionRegistry definitionRegistry;
  @Inject EventBus eventBus;

  void onAlert(@ObservesAsync WatchdogAlertEvent event) {
    UUID caseId = resolveCaseId(event);
    if (caseId == null) {
      LOG.debugf(
          "Watchdog alert %s — no case resolution for target '%s'",
          event.conditionType(), event.targetName());
      return;
    }

    CaseInstance instance = caseInstanceCache.get(caseId);
    if (instance == null || instance.getState().isTerminal()) {
      LOG.debugf(
          "Watchdog alert %s — case %s not found or terminal", event.conditionType(), caseId);
      return;
    }

    CaseDefinition definition = definitionRegistry.getCaseDefinition(instance.getCaseMetaModel());
    if (definition == null) {
      LOG.debugf("Watchdog alert %s — no definition for case %s", event.conditionType(), caseId);
      return;
    }

    StallRecoveryPolicy policy = definition.getStallRecoveryPolicy();
    if (policy == null || !policy.enabled()) {
      LOG.debugf(
          "Watchdog alert %s — stall recovery disabled for case %s", event.conditionType(), caseId);
      return;
    }

    StallRecoveryContext context =
        new StallRecoveryContext(
            caseId,
            instance.tenancyId,
            event.conditionType(),
            event.context().affectedAgentIds(),
            event.summary(),
            event.context(),
            event.firedAt(),
            null,
            null);

    eventBus.publish(STALL_RECOVERY_ADDRESS, context);
    LOG.infof("Watchdog alert %s published for case %s", event.conditionType(), caseId);
  }

  UUID resolveCaseId(WatchdogAlertEvent event) {
    UUID caseId = CaseChannel.parseCaseId(event.targetName());
    if (caseId != null) return caseId;

    String channelName = extractChannelName(event.context());
    if (channelName != null) {
      return CaseChannel.parseCaseId(channelName);
    }
    return null;
  }

  static String extractChannelName(AlertContext ctx) {
    return switch (ctx) {
      case BarrierStuckContext c -> c.channelName();
      case LoopDetectedContext c -> c.channelName();
      case ContextPressureContext c -> c.channelName();
      case CircularDelegationContext c -> c.channelName();
      case DeliveryLagContext c -> c.channelName();
      case ObligationFanOutContext c -> c.channelName();
      case ConversationStallContext c -> c.channelName();
      case EchoChamberContext c -> c.channelName();
      case QueueDepthContext c -> c.channelName();
      case ChannelIdleContext c -> c.channelNames().isEmpty() ? null : c.channelNames().getFirst();
      case AgentStaleContext c -> null;
      case ApprovalPendingContext c -> null;
    };
  }
}
