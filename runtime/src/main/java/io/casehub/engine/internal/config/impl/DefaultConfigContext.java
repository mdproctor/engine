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

import io.casehub.engine.internal.config.ConfigContext;
import io.casehub.engine.internal.config.ConfigManager;
import io.casehub.engine.internal.config.SecretManager;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/** Default CDI bean providing access to ConfigManager and SecretManager. */
@ApplicationScoped
public class DefaultConfigContext implements ConfigContext {

  @Inject ConfigManager configManager;

  @Inject SecretManager secretManager;

  @Override
  public ConfigManager configManager() {
    return configManager;
  }

  @Override
  public SecretManager secretManager() {
    return secretManager;
  }
}
