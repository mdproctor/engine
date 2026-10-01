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

import io.casehub.api.spi.improvement.ConflictStrategy;
import io.casehub.api.spi.improvement.DenyPatternProvider;
import io.casehub.api.spi.improvement.ImprovementCategoryProvider;
import io.casehub.api.spi.improvement.ImprovementProposalSource;
import io.casehub.api.spi.improvement.RegressionEvaluator;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.inject.Any;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

@ApplicationScoped
public class EvolutionBootstrap {

  @Inject ImprovementCategoryRegistry categoryRegistry;

  @Inject ImprovementProposalSourceRegistry proposalSourceRegistry;

  @Inject RegressionEvaluatorRegistry regressionEvaluatorRegistry;

  @Inject ConflictStrategyRegistry conflictStrategyRegistry;

  @Inject DenyPatternProviderRegistry denyPatternProviderRegistry;

  @Inject @Any Instance<ImprovementCategoryProvider> categoryProviders;

  @Inject @Any Instance<ImprovementProposalSource> proposalSources;

  @Inject @Any Instance<RegressionEvaluator> regressionEvaluators;

  @Inject @Any Instance<ConflictStrategy> conflictStrategies;

  @Inject @Any Instance<DenyPatternProvider> denyPatternProviders;

  void onStartup(@Observes StartupEvent event) {
    for (var provider : categoryProviders) {
      categoryRegistry.registerProvider(provider);
    }
    for (var source : proposalSources) {
      proposalSourceRegistry.register(source);
    }
    for (var evaluator : regressionEvaluators) {
      regressionEvaluatorRegistry.register(evaluator);
    }
    for (var strategy : conflictStrategies) {
      conflictStrategyRegistry.register(strategy);
    }
    for (var provider : denyPatternProviders) {
      denyPatternProviderRegistry.register(provider);
    }
  }
}
