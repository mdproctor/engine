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

import io.casehub.engine.common.internal.config.ConfigManager;
import io.casehub.engine.common.internal.config.SecretManager;
import io.casehub.engine.common.internal.config.SecretNotFoundException;
import java.util.HashMap;
import java.util.Map;

public class ConfigSecretManager implements SecretManager {

  private final ConfigManager configManager;

  public ConfigSecretManager(ConfigManager configManager) {
    this.configManager = configManager;
  }

  @Override
  public Map<String, Object> secret(String secretName) {
    String prefix = secretName + ".";
    Map<String, Object> result = new HashMap<>();

    for (String propName : configManager.names()) {
      if (propName.startsWith(prefix)) {
        String key = propName.substring(prefix.length());
        configManager
            .config(propName, String.class)
            .ifPresent(value -> putNested(result, key, value));
      }
    }

    if (result.isEmpty()) {
      throw new SecretNotFoundException(secretName);
    }

    return result;
  }

  @SuppressWarnings("unchecked")
  private void putNested(Map<String, Object> map, String key, Object value) {
    String[] parts = key.split("\\.", 2);
    if (parts.length == 1) {
      map.put(key, value);
    } else {
      Map<String, Object> nested =
          (Map<String, Object>) map.computeIfAbsent(parts[0], k -> new HashMap<>());
      putNested(nested, parts[1], value);
    }
  }
}
