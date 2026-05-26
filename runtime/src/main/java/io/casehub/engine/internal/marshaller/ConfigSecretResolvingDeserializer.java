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
package io.casehub.engine.internal.marshaller;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.deser.std.StdScalarDeserializer;
import io.casehub.engine.common.internal.config.ConfigContext;
import io.casehub.engine.common.internal.config.SecretNotFoundException;
import java.io.IOException;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Jackson deserializer that resolves config and secret placeholders during YAML deserialization.
 *
 * <p>Replaces placeholders with actual values from ConfigContext:
 *
 * <ul>
 *   <li><code>${$secret.openai.apiKey}</code> → resolves to secret value from SecretManager
 *   <li><code>${$config.timeout}</code> → resolves to config value from ConfigManager
 * </ul>
 *
 * <p>Resolution happens once at YAML deserialization time, not at runtime.
 *
 * <p>Example:
 *
 * <pre>
 * # YAML
 * apiKey: "${$secret.openai.apiKey}"
 * timeout: "${$config.worker.timeout}"
 *
 * # Deserialized with actual values
 * apiKey: "sk-proj-abc123..."
 * timeout: "30"
 * </pre>
 */
public class ConfigSecretResolvingDeserializer extends StdScalarDeserializer<String> {

  // Pattern: ${$secret.name.property} or ${$config.key}
  private static final Pattern PLACEHOLDER_PATTERN =
      Pattern.compile("\\$\\{\\$(secret|config)\\.([^}]+)\\}");

  private final ConfigContext configContext;

  public ConfigSecretResolvingDeserializer(ConfigContext configContext) {
    super(String.class);
    this.configContext = configContext;
  }

  @Override
  public String deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
    String value = p.getValueAsString();
    if (value == null || value.isEmpty()) {
      return value;
    }

    return resolvePlaceholders(value);
  }

  /**
   * Resolves all ${$secret.*} and ${$config.*} placeholders in the string.
   *
   * @param value string potentially containing placeholders
   * @return string with all placeholders replaced by actual values
   */
  private String resolvePlaceholders(String value) {
    Matcher matcher = PLACEHOLDER_PATTERN.matcher(value);
    StringBuffer result = new StringBuffer();

    while (matcher.find()) {
      String type = matcher.group(1); // "secret" or "config"
      String path = matcher.group(2); // "openai.apiKey" or "timeout"

      String resolvedValue = resolvePlaceholder(type, path);
      matcher.appendReplacement(result, Matcher.quoteReplacement(resolvedValue));
    }

    matcher.appendTail(result);
    return result.toString();
  }

  /**
   * Resolves a single placeholder.
   *
   * @param type "secret" or "config"
   * @param path property path (e.g., "openai.apiKey" for secrets, "timeout" for config)
   * @return resolved value as string
   */
  private String resolvePlaceholder(String type, String path) {
    if ("secret".equals(type)) {
      return resolveSecret(path);
    } else if ("config".equals(type)) {
      return resolveConfig(path);
    } else {
      throw new IllegalArgumentException("Unknown placeholder type: " + type);
    }
  }

  /**
   * Resolves ${$secret.name.property} placeholder.
   *
   * <p>Pattern: ${$secret.openai.apiKey} where "openai" is secret name and "apiKey" is property.
   *
   * @param path full path like "openai.apiKey"
   * @return secret value as string
   */
  private String resolveSecret(String path) {
    String[] parts = path.split("\\.", 2);
    if (parts.length < 2) {
      throw new IllegalArgumentException(
          "Secret path must be in format 'secretName.property', got: " + path);
    }

    String secretName = parts[0];
    String propertyPath = parts[1];

    Map<String, Object> secret = configContext.secretManager().secret(secretName);
    Object value = getNestedProperty(secret, propertyPath);

    if (value == null) {
      throw new SecretNotFoundException(
          secretName + " (property '" + propertyPath + "' not found)");
    }

    return value.toString();
  }

  /**
   * Resolves ${$config.key} placeholder.
   *
   * @param key config key (e.g., "timeout", "worker.retries")
   * @return config value as string
   */
  private String resolveConfig(String key) {
    return configContext
        .configManager()
        .config(key, String.class)
        .orElseThrow(
            () ->
                new IllegalArgumentException(
                    "Config property not found: " + key + " (use ${$config." + key + "})"));
  }

  /**
   * Gets nested property from map by path.
   *
   * <p>Example: getNestedProperty({a: {b: "value"}}, "a.b") → "value"
   *
   * @param map source map
   * @param path property path with dots
   * @return property value or null if not found
   */
  @SuppressWarnings("unchecked")
  private Object getNestedProperty(Map<String, Object> map, String path) {
    String[] parts = path.split("\\.");
    Object current = map;

    for (String part : parts) {
      if (!(current instanceof Map)) {
        return null;
      }
      current = ((Map<String, Object>) current).get(part);
      if (current == null) {
        return null;
      }
    }

    return current;
  }
}
