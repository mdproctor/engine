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
package io.casehub.engine.internal.callback;

import io.casehub.api.spi.ActionRiskClassifier;
import io.casehub.api.spi.ChainedActionRiskClassifier;
import io.casehub.api.spi.ClassificationContext;
import io.casehub.api.spi.RiskClassifier;
import io.casehub.api.spi.RiskDecision;
import io.casehub.api.spi.RiskDecision.Autonomous;
import io.casehub.api.spi.RiskDecision.GateRequired;
import io.casehub.platform.api.callback.CallbackRegistration;
import io.casehub.platform.api.callback.CallbackRegistry;
import io.casehub.platform.api.identity.CurrentPrincipal;
import io.casehub.platform.callback.CallbackInvoker;
import io.casehub.worker.api.PlannedAction;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.List;
import org.jboss.logging.Logger;

/**
 * Fans out to all remote {@link ActionRiskClassifier} callback registrations and returns the
 * most-restrictive {@link RiskDecision}. Joins the {@link ChainedActionRiskClassifier} chain via
 * the {@link RiskClassifier} qualifier.
 */
@RiskClassifier
@ApplicationScoped
public class CallbackActionRiskClassifier implements ActionRiskClassifier {

  private static final Logger LOG = Logger.getLogger(CallbackActionRiskClassifier.class);
  private static final String SPI_NAME = "action-risk-classifier";

  static final GateRequired FAIL_SAFE =
      new GateRequired(
          "Remote classifier error — manual review required", true, null, null, null, null, null);

  private final CallbackRegistry callbackRegistry;
  private final CallbackInvoker callbackInvoker;
  private final CurrentPrincipal currentPrincipal;

  @Inject
  CallbackActionRiskClassifier(
      final CallbackRegistry callbackRegistry,
      final CallbackInvoker callbackInvoker,
      final CurrentPrincipal currentPrincipal) {
    this.callbackRegistry = callbackRegistry;
    this.callbackInvoker = callbackInvoker;
    this.currentPrincipal = currentPrincipal;
  }

  @Override
  public RiskDecision classify(final PlannedAction action, final ClassificationContext context) {
    final String tenancyId = currentPrincipal.tenancyId();
    final List<CallbackRegistration> registrations =
        callbackRegistry.findBySpi(SPI_NAME, tenancyId);

    if (registrations.isEmpty()) {
      return new Autonomous();
    }

    RiskDecision result = new Autonomous();
    boolean anyFailed = false;
    for (final CallbackRegistration reg : registrations) {
      try {
        final RiskDecision remote =
            callbackInvoker.invoke(
                reg, "classify", new Object[] {action, context}, RiskDecision.class);
        if (remote != null) {
          result = ChainedActionRiskClassifier.mostRestrictive(result, remote);
        }
      } catch (final Exception e) {
        LOG.warnf(
            e,
            "Remote classifier at %s failed — continuing fan-out, will apply fail-safe if all fail",
            reg.callbackUrl());
        anyFailed = true;
      }
    }
    if (anyFailed && result instanceof Autonomous) {
      return FAIL_SAFE;
    }
    return result;
  }
}
