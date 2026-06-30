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
package io.casehub.api.engine;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.casehub.api.marshaller.YamlMapper;
import io.casehub.api.model.CaseDefinition;
import io.casehub.api.model.converter.CaseDefinitionYamlMapper;
import io.casehub.api.spi.WorkerFunctionProviderRegistry;
import jakarta.inject.Inject;
import java.io.IOException;
import java.io.InputStream;

/**
 * Base class for YAML-backed CaseHub definitions.
 *
 * <p>In CDI contexts, {@link ExpressionEngineRegistry} and {@link ObjectMapper} are injected
 * automatically; all registered expression languages are supported. Outside CDI (tests, tooling),
 * the no-arg constructor path falls back to JQ-only parsing.
 *
 * <p>Subclasses that need to add programmatic workers (backed by CDI-injected services) override
 * {@link #augment(CaseDefinition)} instead of {@code getDefinition()}. The hook is called once,
 * inside the double-checked lock, between YAML loading and caching.
 */
public class YamlCaseHub extends CaseHub {

  @Inject ExpressionEngineRegistry expressionEngineRegistry;

  @Inject @YamlMapper ObjectMapper objectMapper;

  @Inject WorkerFunctionProviderRegistry workerFunctionProviderRegistry;

  private final String path;
  private volatile CaseDefinition definition;

  public YamlCaseHub(final String path) {
    this.path = path;
  }

  @Override
  public final CaseDefinition getDefinition() {
    if (definition == null) {
      synchronized (this) {
        if (definition == null) {
          try (InputStream is =
              Thread.currentThread().getContextClassLoader().getResourceAsStream(path)) {
            if (is == null) {
              throw new IllegalStateException("Resource " + path + " not found on classpath");
            }
            CaseDefinition loaded =
                CaseDefinitionYamlMapper.load(
                    is, objectMapper, expressionEngineRegistry, workerFunctionProviderRegistry);
            augment(loaded);
            definition = loaded;
          } catch (IOException e) {
            throw new RuntimeException("Failed to load CaseHub definition from " + path, e);
          }
        }
      }
    }
    return definition;
  }

  /**
   * Hook for subclasses to augment the YAML-loaded definition with programmatic workers, agent
   * descriptors, or other modifications.
   *
   * <p>Called once, inside the double-checked lock, between YAML loading and caching. CDI-injected
   * fields are available. The default implementation is a no-op.
   *
   * @param definition the loaded definition to augment
   */
  protected void augment(CaseDefinition definition) {}
}
