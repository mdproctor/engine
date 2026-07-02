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
package io.casehub.engine.internal.startup;

import io.casehub.blocks.oversight.ActionRiskClassifier;
import io.casehub.blocks.oversight.RiskClassifier;
import io.quarkus.runtime.Startup;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

/**
 * Startup check that warns if consumer-provided {@link ActionRiskClassifier} beans are registered
 * but {@code casehub-engine-work-adapter} is not on the classpath.
 *
 * <p>If a classifier returns {@link io.casehub.blocks.oversight.RiskDecision.GateRequired} without
 * work-adapter present, the gate WorkItem is never created and the case stalls indefinitely. The
 * warning appears in logs at startup so operators can fix the configuration before it causes
 * production incidents.
 *
 * <p>Detection: uses CDI {@link Instance} unsatisfied check rather than classpath scanning —
 * work-adapter provides {@code ActionGateWorkItemHandler} which injects {@code WorkItemService}. If
 * {@code WorkItemService} is absent (unsatisfied), work-adapter is not on the classpath.
 */
@Startup
@ApplicationScoped
public class ActionGateDeploymentHealthCheck {

  private static final Logger LOG = Logger.getLogger(ActionGateDeploymentHealthCheck.class);

  @Inject @RiskClassifier Instance<ActionRiskClassifier> classifiers;

  /**
   * Checks at startup whether a misconfigured deployment would silently stall cases.
   *
   * <p>The check runs once at application start. No-op when either: (a) no consumer classifiers are
   * registered (the chain returns Autonomous — no gates possible), or (b) work-adapter is on the
   * classpath.
   */
  @PostConstruct
  public void checkConfiguration() {
    if (classifiers.isUnsatisfied()) {
      return; // No @RiskClassifier beans — always Autonomous, no gate possible
    }

    // Consumer classifiers present — verify work-adapter is available.
    // We detect work-adapter by looking for the ActionGateWorkItemHandler class, which is only
    // present when casehub-engine-work-adapter is on the classpath.
    try {
      Class.forName(
          "io.casehub.workadapter.ActionGateWorkItemHandler",
          false,
          Thread.currentThread().getContextClassLoader());
    } catch (ClassNotFoundException e) {
      LOG.warn(
          "CONFIGURATION WARNING: ActionRiskClassifier beans are registered but"
              + " casehub-engine-work-adapter is NOT on the classpath. If any classifier returns"
              + " GateRequired, the gate WorkItem will never be created and the case will stall"
              + " indefinitely. Add casehub-engine-work-adapter to your deployment dependencies.");
    }
  }
}
