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

import io.casehub.api.model.stigmergy.CategoryDescriptor;
import io.casehub.api.model.stigmergy.StageDescriptor;
import io.casehub.api.spi.improvement.ImprovementCategoryProvider;
import io.casehub.engine.common.spi.Resettable;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

@ApplicationScoped
public class ImprovementCategoryRegistry implements Resettable {

  private final ConcurrentHashMap<String, CategoryDescriptor> categories =
      new ConcurrentHashMap<>();
  private final ConcurrentHashMap<String, List<StageDescriptor>> stagesByDomain =
      new ConcurrentHashMap<>();

  public void registerProvider(ImprovementCategoryProvider provider) {
    for (var cat : provider.categories()) {
      categories.put(cat.id(), cat);
    }
    var sortedStages =
        provider.stages().stream()
            .sorted(Comparator.comparingInt(StageDescriptor::ordinal))
            .toList();
    stagesByDomain.put(provider.domainId(), sortedStages);
  }

  public List<CategoryDescriptor> allCategories() {
    return List.copyOf(categories.values());
  }

  public Optional<CategoryDescriptor> getCategory(String categoryId) {
    return Optional.ofNullable(categories.get(categoryId));
  }

  public boolean isGateCheckpoint(String domainId, String stageId) {
    var domainStages = stagesByDomain.get(domainId);
    if (domainStages == null) return false;
    return domainStages.stream().anyMatch(s -> s.id().equals(stageId) && s.gateCheckpoint());
  }

  public List<StageDescriptor> stagesForDomain(String domainId) {
    return stagesByDomain.getOrDefault(domainId, List.of());
  }

  public Optional<String> domainForCategory(String categoryId) {
    var cat = categories.get(categoryId);
    return cat != null ? Optional.of(cat.domainId()) : Optional.empty();
  }

  @Override
  public void reset() {
    categories.clear();
    stagesByDomain.clear();
  }
}
