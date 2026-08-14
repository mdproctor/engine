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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.casehub.api.marshaller.YamlMapper;
import io.casehub.api.model.CaseDefinition;
import io.casehub.api.model.converter.CaseDefinitionYamlMapper;
import io.casehub.api.spi.WorkerFunctionProviderRegistry;
import io.casehub.platform.api.yaml.YamlMerger;
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
 * <p>Supports YAML overlay composition: a base YAML is loaded first, then an optional overlay YAML
 * is deep-merged on top via {@link YamlMerger}. The overlay can be specified explicitly via the
 * two-arg constructor, or discovered by convention ({@code -overrides} suffix in the same
 * directory). After merging, {@link #augment(CaseDefinition)} runs for programmatic modifications.
 *
 * <p>Resolution order: base YAML → overlay YAML (explicit or convention) → augment().
 */
public class YamlCaseHub extends CaseHub {

  @Inject ExpressionEngineRegistry expressionEngineRegistry;

  @Inject @YamlMapper ObjectMapper objectMapper;

  @Inject WorkerFunctionProviderRegistry workerFunctionProviderRegistry;

  private final String path;
  private final String overlayPath;
  private volatile CaseDefinition definition;

  public YamlCaseHub(final String path) {
    this(path, null);
  }

  public YamlCaseHub(final String path, final String overlayPath) {
    this.path = path;
    this.overlayPath = overlayPath;
  }

  @Override
  public final CaseDefinition getDefinition() {
    if (definition == null) {
      synchronized (this) {
        if (definition == null) {
          try {
            JsonNode base = loadYamlAsJsonNode(path);
            JsonNode overlay = resolveOverlay();
            JsonNode merged = (overlay != null) ? YamlMerger.merge(base, overlay) : base;
            CaseDefinition loaded =
                CaseDefinitionYamlMapper.load(
                    merged, objectMapper, expressionEngineRegistry, workerFunctionProviderRegistry);
            augment(loaded);
            definition = loaded;
          } catch (RuntimeException e) {
            throw e;
          } catch (Exception e) {
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

  private JsonNode resolveOverlay() {
    if (overlayPath != null) {
      return loadYamlAsJsonNode(overlayPath);
    }
    String conventionPath = deriveConventionPath(path);
    InputStream is =
        Thread.currentThread().getContextClassLoader().getResourceAsStream(conventionPath);
    if (is != null) {
      try {
        return objectMapper.readTree(is);
      } catch (IOException e) {
        throw new RuntimeException("Failed to load overlay from " + conventionPath, e);
      } finally {
        try {
          is.close();
        } catch (IOException ignored) {
        }
      }
    }
    return null;
  }

  private JsonNode loadYamlAsJsonNode(String resourcePath) {
    try (InputStream is =
        Thread.currentThread().getContextClassLoader().getResourceAsStream(resourcePath)) {
      if (is == null) {
        throw new IllegalStateException("Resource " + resourcePath + " not found on classpath");
      }
      return objectMapper.readTree(is);
    } catch (IOException e) {
      throw new RuntimeException("Failed to read YAML from " + resourcePath, e);
    }
  }

  static String deriveConventionPath(String basePath) {
    int dot = basePath.lastIndexOf('.');
    if (dot < 0) return basePath + "-overrides";
    return basePath.substring(0, dot) + "-overrides" + basePath.substring(dot);
  }
}
