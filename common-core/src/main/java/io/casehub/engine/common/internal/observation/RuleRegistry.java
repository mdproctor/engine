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
package io.casehub.engine.common.internal.observation;

import io.casehub.api.spi.observation.LocalRule;
import io.casehub.api.spi.observation.RuleFiring;
import io.casehub.engine.common.spi.Resettable;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@ApplicationScoped
public class RuleRegistry implements Resettable {

  record RuleEntry(LocalRule rule, String agentId, String bindingName) {}

  private final ConcurrentHashMap<UUID, List<RuleEntry>> rules = new ConcurrentHashMap<>();
  private final ConcurrentHashMap<UUID, ConcurrentHashMap<String, List<RuleFiring>>> firings =
      new ConcurrentHashMap<>();

  public String registerRule(
      UUID caseId, String agentId, String bindingName, LocalRule rule, int maxPerCase) {
    var caseRules =
        rules.computeIfAbsent(caseId, k -> Collections.synchronizedList(new ArrayList<>()));
    synchronized (caseRules) {
      caseRules.removeIf(
          e ->
              e.agentId().equals(agentId)
                  && e.bindingName().equals(bindingName)
                  && e.rule().id().equals(rule.id()));
      if (caseRules.size() >= maxPerCase) {
        return null;
      }
      caseRules.add(new RuleEntry(rule, agentId, bindingName));
      return rule.id();
    }
  }

  public void deregisterRule(UUID caseId, String ruleId) {
    var caseRules = rules.get(caseId);
    if (caseRules != null) {
      synchronized (caseRules) {
        caseRules.removeIf(e -> e.rule().id().equals(ruleId));
      }
    }
  }

  public Map<String, List<LocalRule>> getRulesForCase(UUID caseId) {
    var caseRules = rules.get(caseId);
    if (caseRules == null) {
      return Map.of();
    }
    Map<String, List<LocalRule>> result = new LinkedHashMap<>();
    synchronized (caseRules) {
      for (var entry : caseRules) {
        result.computeIfAbsent(entry.agentId(), k -> new ArrayList<>()).add(entry.rule());
      }
    }
    return result;
  }

  public List<LocalRule> getRulesForAgent(UUID caseId, String agentId) {
    var caseRules = rules.get(caseId);
    if (caseRules == null) {
      return List.of();
    }
    synchronized (caseRules) {
      return caseRules.stream()
          .filter(e -> e.agentId().equals(agentId))
          .map(RuleEntry::rule)
          .toList();
    }
  }

  public void storeFirings(UUID caseId, String agentId, List<RuleFiring> agentFirings) {
    firings
        .computeIfAbsent(caseId, k -> new ConcurrentHashMap<>())
        .put(agentId, List.copyOf(agentFirings));
  }

  public List<RuleFiring> getFirings(UUID caseId, String agentId) {
    var caseFirings = firings.get(caseId);
    if (caseFirings == null) {
      return List.of();
    }
    return caseFirings.getOrDefault(agentId, List.of());
  }

  public void unregisterByAgent(UUID caseId, String agentId) {
    var caseRules = rules.get(caseId);
    if (caseRules != null) {
      synchronized (caseRules) {
        caseRules.removeIf(e -> e.agentId().equals(agentId));
      }
    }
  }

  public void unregisterByBinding(UUID caseId, Set<String> bindingNames) {
    var caseRules = rules.get(caseId);
    if (caseRules != null) {
      synchronized (caseRules) {
        caseRules.removeIf(e -> bindingNames.contains(e.bindingName()));
      }
    }
  }

  public void evictByCase(UUID caseId) {
    rules.remove(caseId);
    firings.remove(caseId);
  }

  public int ruleCount(UUID caseId) {
    var caseRules = rules.get(caseId);
    return caseRules == null ? 0 : caseRules.size();
  }

  @Override
  public void reset() {
    rules.clear();
    firings.clear();
  }
}
