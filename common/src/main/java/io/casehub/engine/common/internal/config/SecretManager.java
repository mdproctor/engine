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

import java.util.Map;

/**
 * Resolves secrets from various backends (system properties, K8s Secrets, Vault, etc.).
 *
 * <p>Adapted from Serverless Workflow SecretManager. Accessible from JQ expressions via {@code
 * $secret.{secretName}.{property}} syntax.
 *
 * <p>Default implementation (ConfigSecretManager) builds secrets from ConfigManager by filtering
 * properties with {@code secretName.} prefix and creating nested maps.
 *
 * <p>Example:
 *
 * <pre>
 * # application.properties
 * openai.apiKey=sk-test
 * openai.organizationId=org-123
 *
 * # JQ expression in YAML
 * apiKey: "${$secret.openai.apiKey}"  → resolves to "sk-test"
 * </pre>
 */
public interface SecretManager {

  /**
   * Resolve a secret by name.
   *
   * @param secretName secret identifier (e.g., "openai", "database")
   * @return map of secret properties (e.g., {apiKey: "sk-...", orgId: "..."})
   * @throws SecretNotFoundException if secret does not exist
   */
  Map<String, Object> secret(String secretName);
}
