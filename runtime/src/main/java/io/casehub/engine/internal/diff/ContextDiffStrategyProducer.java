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
package io.casehub.engine.internal.diff;

import io.casehub.api.spi.ContextDiffStrategy;
import io.quarkus.arc.DefaultBean;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import org.eclipse.microprofile.config.inject.ConfigProperty;

@ApplicationScoped
class ContextDiffStrategyProducer {

  @ConfigProperty(name = "casehub.engine.diff-strategy", defaultValue = "none")
  String strategy;

  @Produces
  @DefaultBean
  @ApplicationScoped
  ContextDiffStrategy produce() {
    return switch (strategy) {
      case "none" -> new NoOpContextDiffStrategy();
      case "top-level" -> new TopLevelContextDiffStrategy();
      case "json-patch" -> new JsonPatchContextDiffStrategy();
      default ->
          throw new IllegalStateException(
              "Unknown casehub.engine.diff-strategy: '"
                  + strategy
                  + "'. Valid values: none, top-level, json-patch");
    };
  }
}
