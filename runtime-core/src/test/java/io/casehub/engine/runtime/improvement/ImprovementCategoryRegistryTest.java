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

import io.casehub.api.model.stigmergy.CategoryDescriptor;
import io.casehub.api.model.stigmergy.StageDescriptor;
import io.casehub.api.spi.improvement.ImprovementCategoryProvider;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ImprovementCategoryRegistryTest {

  private ImprovementCategoryRegistry registry;

  @BeforeEach
  void setUp() {
    registry = new ImprovementCategoryRegistry();
  }

  @Test
  void registerProviderPopulatesCategoriesAndStages() {
    registry.registerProvider(new CodeEvolutionCategoryProvider());
    assertThat(registry.allCategories()).hasSize(5);
    assertThat(registry.stagesForDomain("code-evolution")).hasSize(11);
    assertThat(registry.isGateCheckpoint("code-evolution", "research-scope")).isTrue();
    assertThat(registry.isGateCheckpoint("code-evolution", "introspect")).isFalse();
    assertThat(registry.domainForCategory("dependency-update")).hasValue("code-evolution");
  }

  @Test
  void multiProviderCoexistence() {
    registry.registerProvider(new CodeEvolutionCategoryProvider());
    registry.registerProvider(tradingProvider());

    assertThat(registry.allCategories()).hasSize(6);
    assertThat(registry.stagesForDomain("code-evolution")).hasSize(11);
    assertThat(registry.stagesForDomain("trading")).hasSize(2);
    assertThat(registry.domainForCategory("parameter-tuning")).hasValue("trading");
    assertThat(registry.domainForCategory("dependency-update")).hasValue("code-evolution");
  }

  @Test
  void getCategoryReturnsDescriptor() {
    registry.registerProvider(new CodeEvolutionCategoryProvider());
    var cat = registry.getCategory("lint-fix");
    assertThat(cat).isPresent();
    assertThat(cat.get().domainId()).isEqualTo("code-evolution");
  }

  @Test
  void unknownCategoryReturnsEmpty() {
    assertThat(registry.getCategory("nonexistent")).isEmpty();
  }

  @Test
  void resetClearsAll() {
    registry.registerProvider(new CodeEvolutionCategoryProvider());
    assertThat(registry.allCategories()).isNotEmpty();
    registry.reset();
    assertThat(registry.allCategories()).isEmpty();
    assertThat(registry.stagesForDomain("code-evolution")).isEmpty();
  }

  private ImprovementCategoryProvider tradingProvider() {
    return new ImprovementCategoryProvider() {
      @Override
      public String domainId() {
        return "trading";
      }

      @Override
      public List<CategoryDescriptor> categories() {
        return List.of(
            new CategoryDescriptor(
                "parameter-tuning", "Parameter Tuning", "Tune trading parameters", "trading"));
      }

      @Override
      public List<StageDescriptor> stages() {
        return List.of(
            new StageDescriptor("backtest", "Backtest", 0, true, "trading"),
            new StageDescriptor("deploy", "Deploy", 1, false, "trading"));
      }
    };
  }
}
