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
package io.casehub.engine.annotations.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.casehub.worker.api.WorkerFunction;
import io.casehub.worker.api.WorkerResult;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class AnnotationWorkerFunction {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private AnnotationWorkerFunction() {}

  public static WorkerFunction.Sync<Map, Map> create(
      String implClassName,
      String methodName,
      List<WorkerParamDescriptor> params,
      String returnTypeName,
      String effectKey) {

    return new WorkerFunction.Sync<>(
        Map.class,
        Map.class,
        (input, scope) -> {
          try {
            Class<?> implClass =
                Thread.currentThread().getContextClassLoader().loadClass(implClassName);
            Object instance = implClass.getDeclaredConstructor().newInstance();

            Class<?>[] paramTypes = new Class<?>[params.size()];
            Object[] args = new Object[params.size()];
            for (int i = 0; i < params.size(); i++) {
              WorkerParamDescriptor p = params.get(i);
              paramTypes[i] =
                  Thread.currentThread().getContextClassLoader().loadClass(p.typeName());
              Object rawValue = input != null ? input.get(p.contextKey()) : null;
              args[i] = MAPPER.convertValue(rawValue, paramTypes[i]);
            }

            Method method = implClass.getMethod(methodName, paramTypes);
            Object result = method.invoke(instance, args);

            Map<String, Object> output = new HashMap<>();
            if (result != null && effectKey != null) {
              output.put(effectKey, MAPPER.convertValue(result, Map.class));
            }
            return WorkerResult.of(output);
          } catch (Exception e) {
            return WorkerResult.failed("Annotation worker invocation failed: " + e.getMessage());
          }
        });
  }
}
