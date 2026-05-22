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
package io.casehub.engine.internal.jq;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.casehub.engine.internal.config.ConfigManager;
import io.casehub.engine.internal.config.SecretManager;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.thisptr.jackson.jq.BuiltinFunctionLoader;
import net.thisptr.jackson.jq.JsonQuery;
import net.thisptr.jackson.jq.Scope;
import net.thisptr.jackson.jq.Versions;

/**
 * JQ expression evaluator with support for $secret and $config scope variables.
 *
 * <p>Scope variables:
 *
 * <ul>
 *   <li>$secret.{name}.{property} - resolves secrets via SecretManager
 *   <li>$config.{name}.{property} - resolves config maps via ConfigManager
 * </ul>
 *
 * <p>Inspired by CNCF Serverless Workflow JQ scope injection pattern.
 */
@ApplicationScoped
public class JQEvaluator {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  @Inject SecretManager secretManager;

  @Inject ConfigManager configManager;

  private Scope rootScope;

  @PostConstruct
  void init() {
    rootScope = Scope.newEmptyScope();
    BuiltinFunctionLoader.getInstance().loadFunctions(Versions.JQ_1_6, rootScope);
  }

  /**
   * Evaluate JQ expression without secrets/configs.
   *
   * @param jqExpr JQ expression
   * @param asNode input JSON node
   * @return validation result
   */
  public ValidationResult eval(String jqExpr, JsonNode asNode) {
    return eval(jqExpr, asNode, Set.of(), Set.of());
  }

  /**
   * Evaluate JQ expression with $secret and $config scope variables.
   *
   * @param jqExpr JQ expression
   * @param asNode input JSON node
   * @param secretNames secret names to load (from use.secrets)
   * @param configMapNames config map names to load (from use.configMaps)
   * @return validation result
   */
  public ValidationResult eval(
      String jqExpr, JsonNode asNode, Set<String> secretNames, Set<String> configMapNames) {
    try {
      Scope childScope = Scope.newChildScope(rootScope);

      // Inject $secret scope variable
      if (!secretNames.isEmpty()) {
        Map<String, Object> secretsMap = new HashMap<>();
        for (String secretName : secretNames) {
          Map<String, Object> secret = secretManager.secret(secretName);
          secretsMap.put(secretName, secret);
        }
        JsonNode secretNode = MAPPER.valueToTree(secretsMap);
        childScope.setValue("secret", secretNode);
      }

      // Inject $config scope variable
      if (!configMapNames.isEmpty()) {
        Map<String, Object> configsMap = new HashMap<>();
        for (String configMapName : configMapNames) {
          Map<String, Object> configMap = configManager.configMap(configMapName);
          configsMap.put(configMapName, configMap);
        }
        JsonNode configNode = MAPPER.valueToTree(configsMap);
        childScope.setValue("config", configNode);
      }

      JsonQuery query = JsonQuery.compile(jqExpr, Versions.JQ_1_6);

      List<JsonNode> out = new ArrayList<>();
      query.apply(childScope, asNode, out::add);

      return ValidationResult.ok(out);
    } catch (Exception e) {
      return ValidationResult.error(e.getClass().getSimpleName() + ": " + e.getMessage());
    }
  }
}
