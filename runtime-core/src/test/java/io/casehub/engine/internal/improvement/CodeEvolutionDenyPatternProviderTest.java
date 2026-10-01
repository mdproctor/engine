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
package io.casehub.engine.internal.improvement;

import static org.assertj.core.api.Assertions.assertThat;

import io.casehub.api.model.improvement.ImprovementBudget;
import io.casehub.api.model.improvement.ImprovementConfig;
import io.casehub.api.model.improvement.ImprovementRequest;
import io.casehub.engine.common.spi.DenyPatternStore;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class CodeEvolutionDenyPatternProviderTest {

  private InMemoryDenyPatternStore denyPatternStore;
  private CodeEvolutionDenyPatternProvider provider;
  private final UUID caseId = UUID.randomUUID();
  private final ImprovementConfig config =
      new ImprovementConfig("improvement", 2, null, null, null);

  @BeforeEach
  void setUp() {
    denyPatternStore = new InMemoryDenyPatternStore();
    provider = new CodeEvolutionDenyPatternProvider(denyPatternStore);
  }

  @Test
  void domainIdIsCodeEvolution() {
    assertThat(provider.domainId()).isEqualTo("code-evolution");
  }

  @Test
  void structuralPatternDenied() {
    var request = makeRequest(List.of("src/ImprovementBudgetEnforcer.java"));
    assertThat(provider.isDenied(caseId, "t1", request, config)).isTrue();
  }

  @Test
  void dynamicPatternDenied() {
    denyPatternStore.save(caseId, "config/settings", "t1");
    var request = makeRequest(List.of("config/settings.yaml"));
    assertThat(provider.isDenied(caseId, "t1", request, config)).isTrue();
  }

  @Test
  void configDeniedPathsGlobMatching() {
    var budget =
        new ImprovementBudget(null, null, null, null, List.of("src/main/resources/**"), null, null);
    var configWithBudget = new ImprovementConfig("improvement", 2, null, budget, null);
    var request = makeRequest(List.of("src/main/resources/app.properties"));
    assertThat(provider.isDenied(caseId, "t1", request, configWithBudget)).isTrue();
  }

  @Test
  void allowedReposCheckDeniesUnlisted() {
    var budget =
        new ImprovementBudget(null, null, null, List.of("casehubio/engine"), null, null, null);
    var configWithBudget = new ImprovementConfig("improvement", 2, null, budget, null);
    var request = makeRequestWithRepo(List.of("src/Foo.java"), "casehubio/other");
    assertThat(provider.isDenied(caseId, "t1", request, configWithBudget)).isTrue();
  }

  @Test
  void allowedReposCheckPermitsListed() {
    var budget =
        new ImprovementBudget(null, null, null, List.of("casehubio/engine"), null, null, null);
    var configWithBudget = new ImprovementConfig("improvement", 2, null, budget, null);
    var request = makeRequestWithRepo(List.of("src/Foo.java"), "casehubio/engine");
    assertThat(provider.isDenied(caseId, "t1", request, configWithBudget)).isFalse();
  }

  @Test
  void nonDeniedPathReturnsFalse() {
    var request = makeRequest(List.of("src/main/UserService.java"));
    assertThat(provider.isDenied(caseId, "t1", request, config)).isFalse();
  }

  @Test
  void newInfrastructureClassesDenied() {
    var request = makeRequest(List.of("src/CodeEvolutionCategoryProvider.java"));
    assertThat(provider.isDenied(caseId, "t1", request, config)).isTrue();
  }

  private ImprovementRequest makeRequest(List<String> paths) {
    return new ImprovementRequest(
        "upgrade", "dep", "target", 5, CodeEvolutionMetadata.encode(null, paths), "code-evolution");
  }

  private ImprovementRequest makeRequestWithRepo(List<String> paths, String repo) {
    return new ImprovementRequest(
        "upgrade", "dep", "target", 5, CodeEvolutionMetadata.encode(repo, paths), "code-evolution");
  }

  static class InMemoryDenyPatternStore implements DenyPatternStore {
    private final ConcurrentHashMap<String, Set<String>> patterns = new ConcurrentHashMap<>();

    @Override
    public void save(UUID caseId, String pattern, String tenancyId) {
      patterns.computeIfAbsent(caseId + ":" + tenancyId, k -> new HashSet<>()).add(pattern);
    }

    @Override
    public void remove(UUID caseId, String pattern, String tenancyId) {
      var set = patterns.get(caseId + ":" + tenancyId);
      if (set != null) set.remove(pattern);
    }

    @Override
    public Set<String> findAll(UUID caseId, String tenancyId) {
      return patterns.getOrDefault(caseId + ":" + tenancyId, Set.of());
    }
  }
}
