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
package io.casehub.engine.yamlcbr;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.casehub.engine.flow.CallableDispatchRegistry;
import io.casehub.engine.flow.CallableDispatcher;
import io.casehub.yaml.core.condition.ConditionEvaluator;
import io.casehub.yaml.core.resolver.VariableResolver;
import io.casehub.yaml.core.resolver.VariableSource;
import io.casehub.yaml.jackson.YamlMappers;
import io.casehub.yaml.plugin.api.PluginRegistry;
import io.casehub.yaml.plugin.api.Result;
import io.casehub.yaml.step.catalog.ResolvedStep;
import io.casehub.yaml.step.catalog.StepWalker;
import io.casehub.yaml.step.eval.StructuralStepEvaluator;
import jakarta.annotation.PostConstruct;
import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import org.jboss.logging.Logger;

public class StepFileCallableDispatcher implements CallableDispatcher {

  static final String CALL_NAME = "casehub:step-file";
  private static final Logger LOG = Logger.getLogger(StepFileCallableDispatcher.class);
  private static final ObjectMapper YAML_MAPPER = YamlMappers.create();

  private final PluginRegistry pluginRegistry;
  private final CallableDispatchRegistry dispatchRegistry;

  public StepFileCallableDispatcher(
      PluginRegistry pluginRegistry, CallableDispatchRegistry dispatchRegistry) {
    this.pluginRegistry = pluginRegistry;
    this.dispatchRegistry = dispatchRegistry;
  }

  @PostConstruct
  void register() {
    dispatchRegistry.register(CALL_NAME, this);
  }

  @Override
  public CompletableFuture<Map<String, Object>> dispatch(
      String workflowInstanceId, Map<String, Object> args) {
    return CompletableFuture.supplyAsync(() -> execute(args));
  }

  @SuppressWarnings("unchecked")
  Map<String, Object> execute(Map<String, Object> args) {
    String filePath = (String) args.get("file");
    if (filePath == null || filePath.isBlank()) {
      throw new IllegalArgumentException("casehub:step-file requires 'file' parameter");
    }

    try (InputStream is =
        Thread.currentThread().getContextClassLoader().getResourceAsStream(filePath)) {
      if (is == null) {
        throw new IllegalArgumentException("Step file not found on classpath: " + filePath);
      }

      Map<String, Object> doc = YAML_MAPPER.readValue(is, Map.class);
      List<Map<String, Object>> steps = (List<Map<String, Object>>) doc.get("steps");
      if (steps == null || steps.isEmpty()) {
        return Map.of();
      }

      List<ResolvedStep> resolved = StepWalker.resolve(steps, pluginRegistry);
      VariableSource inputSource =
          key -> args.containsKey(key) ? String.valueOf(args.get(key)) : null;
      VariableResolver resolver =
          new VariableResolver(Map.of("input", inputSource), java.util.Set.of());
      StructuralStepEvaluator evaluator = new StructuralStepEvaluator(new ConditionEvaluator(null));

      Map<String, Object> outputs = new LinkedHashMap<>();
      for (ResolvedStep step : resolved) {
        Result result =
            evaluator.evaluate(
                step,
                resolver,
                (s, r) -> {
                  if (s instanceof ResolvedStep.PluginStep ps) {
                    return pluginRegistry
                        .resolve(ps.actionName())
                        .map(def -> def.action().execute(ps.params(), null))
                        .orElse(Result.of(Map.of()));
                  }
                  return Result.of(Map.of());
                });
        if (result.isSuccess()) {
          outputs.putAll(result.output());
        } else if (result instanceof Result.Failure f) {
          LOG.warnf("Step '%s' failed in %s: %s", step.name(), filePath, f.message());
        }
      }
      return outputs;
    } catch (Exception e) {
      throw new RuntimeException("Failed to execute step file: " + filePath, e);
    }
  }
}
