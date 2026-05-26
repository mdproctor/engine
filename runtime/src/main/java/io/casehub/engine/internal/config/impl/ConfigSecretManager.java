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
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.HashMap;
import java.util.Map;

/**
 * Builds secrets from ConfigManager by filtering properties with prefix.
 *
 * <p>Adapted from Serverless Workflow ConfigSecretManager.
 *
 * <p>Example:
 *
 * <pre>
 * openai.apiKey=sk-test
 * openai.organizationId=org-123
 * openai.model.name=gpt-4o
 * </pre>
 *
 * becomes:
 *
 * <pre>
 * {
 *   "apiKey": "sk-test",
 *   "organizationId": "org-123",
 *   "model": {
 *     "name": "gpt-4o"
 *   }
 * }
 * </pre>
 */
@ApplicationScoped
public class ConfigSecretManager implements SecretManager {

  @Inject ConfigManager configManager;

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

  /**
   * Converts "enemy.name" -> nested map {enemy: {name: value}}.
   *
   * <p>Algorithm adapted from Serverless Workflow ConfigSecretManager.
   */
  private void putNested(Map<String, Object> map, String key, Object value) {
    String[] parts = key.split("\\.", 2);
    if (parts.length == 1) {
      map.put(key, value);
    } else {
      @SuppressWarnings("unchecked")
      Map<String, Object> nested =
          (Map<String, Object>) map.computeIfAbsent(parts[0], k -> new HashMap<>());
      putNested(nested, parts[1], value);
    }
  }
}
