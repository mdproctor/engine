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
package io.casehub.engine.internal.observation;

import io.casehub.api.engine.RuleSpace;
import io.casehub.api.spi.observation.LocalRule;
import io.casehub.api.spi.observation.RuleConfig;
import io.casehub.api.spi.observation.RuleFiring;
import io.casehub.api.spi.observation.RuleRegistration;
import io.casehub.engine.common.internal.observation.RuleRegistry;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public class DefaultRuleSpace implements RuleSpace {

  private final RuleRegistry registry;
  private final UUID caseId;
  private final String agentId;
  private final String bindingName;
  private final RuleConfig config;

  public DefaultRuleSpace(
      RuleRegistry registry, UUID caseId, String agentId, String bindingName, RuleConfig config) {
    this.registry = registry;
    this.caseId = caseId;
    this.agentId = agentId;
    this.bindingName = bindingName;
    this.config = config;
  }

  @Override
  public RuleRegistration register(LocalRule rule) {
    String ruleId =
        registry.registerRule(caseId, agentId, bindingName, rule, config.maxRulesPerCase());
    if (ruleId == null) {
      return null;
    }
    return new RuleRegistration(ruleId, rule, Instant.now());
  }

  @Override
  public void deregister(String ruleId) {
    registry.deregisterRule(caseId, ruleId);
  }

  @Override
  public List<RuleRegistration> mine() {
    return registry.getRulesForAgent(caseId, agentId).stream()
        .map(r -> new RuleRegistration(r.id(), r, Instant.now()))
        .toList();
  }

  @Override
  public List<RuleFiring> lastFired() {
    return registry.getFirings(caseId, agentId);
  }
}
