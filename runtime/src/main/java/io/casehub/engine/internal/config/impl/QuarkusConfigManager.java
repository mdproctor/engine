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
package io.casehub.engine.internal.config.impl;

import io.casehub.engine.internal.config.ConfigManager;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.Collection;
import java.util.Collections;
import java.util.Optional;
import org.eclipse.microprofile.config.Config;

/**
 * ConfigManager implementation that wraps Quarkus MicroProfile Config.
 *
 * <p>Resolution order (via Quarkus Config):
 *
 * <ol>
 *   <li>System properties (-Dfoo=bar)
 *   <li>Environment variables
 *   <li>application.properties
 *   <li>ConfigSources (K8s ConfigMaps, etc.)
 * </ol>
 */
@ApplicationScoped
public class QuarkusConfigManager implements ConfigManager {

  @Inject Config config;

  @Override
  public <T> Optional<T> config(String propName, Class<T> propClass) {
    return config.getOptionalValue(propName, propClass);
  }

  @Override
  public <T> Collection<T> multiConfig(String propName, Class<T> propClass) {
    return config.getOptionalValues(propName, propClass).orElse(Collections.emptyList());
  }

  @Override
  public Iterable<String> names() {
    return config.getPropertyNames();
  }
}
