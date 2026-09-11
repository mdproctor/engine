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
package io.casehub.engine.common.internal.config;

import java.util.Collection;
import java.util.Map;
import java.util.Optional;

/**
 * Provides access to configuration properties.
 *
 * <p>Adapted from Serverless Workflow ConfigManager with Quarkus integration. Used programmatically
 * from Java code, AND accessible from JQ expressions via {@code $config.{configMapName}.{property}}
 * syntax.
 *
 * <p>Default implementation wraps MicroProfile Config API (application.properties, system
 * properties, environment variables, ConfigSources).
 */
public interface ConfigManager {

  /**
   * Get a single config value.
   *
   * @param propName property name (e.g., "casehub.timeout")
   * @param propClass target type (String, Integer, Boolean, etc.)
   * @return value if present
   */
  <T> Optional<T> config(String propName, Class<T> propClass);

  /**
   * Get a multi-valued config (comma-separated).
   *
   * @param propName property name
   * @param propClass element type
   * @return collection of values (empty if not found)
   */
  <T> Collection<T> multiConfig(String propName, Class<T> propClass);

  /**
   * List all known property names.
   *
   * @return iterable of property names
   */
  Iterable<String> names();

  /**
   * Resolve a config map by name.
   *
   * <p>Builds a nested map from all properties with {@code configMapName.} prefix.
   *
   * <p>Example:
   *
   * <pre>
   * # application.properties
   * app-config.timeout=5000
   * app-config.retries=3
   * app-config.database.host=localhost
   *
   * # JQ expression in YAML
   * timeout: "${$config.\"app-config\".timeout}"  → resolves to "5000"
   * </pre>
   *
   * @param configMapName config map identifier (e.g., "app-config", "feature-flags")
   * @return map of config properties with nested structure (e.g., {timeout: "5000", database:
   *     {host: "localhost"}})
   * @throws ConfigMapNotFoundException if no properties with the prefix exist
   */
  Map<String, Object> configMap(String configMapName);
}
