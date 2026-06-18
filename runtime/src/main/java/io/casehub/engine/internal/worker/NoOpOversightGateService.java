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
package io.casehub.engine.internal.worker;

import io.casehub.api.spi.GateDecision;
import io.casehub.api.spi.OversightGateService;
import io.quarkus.arc.DefaultBean;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import java.util.UUID;
import org.jboss.logging.Logger;

@DefaultBean
@ApplicationScoped
public class NoOpOversightGateService implements OversightGateService {

  private static final Logger LOG = Logger.getLogger(NoOpOversightGateService.class);

  void onStart(@Observes StartupEvent event) {
    LOG.warn(
        "OversightGateService: no implementation configured — all actions proceed autonomously.");
  }

  @Override
  public GateDecision openGate(
      String agentId, String commitmentId, String outcome, String tenancyId) {
    return new GateDecision.Autonomous();
  }

  @Override
  public void fulfill(UUID gateId, String rawOutput) {}
}
