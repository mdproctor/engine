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
package io.casehub.api.model;

import io.casehub.worker.api.WorkerFunction;
import io.casehub.worker.api.WorkerOutcome;
import io.casehub.worker.api.WorkerResult;
import java.util.LinkedHashMap;
import java.util.Map;

/** Utilities for composing worker functions into larger flows. */
public final class WorkerFunctions {

  private WorkerFunctions() {}

  @SuppressWarnings({"unchecked", "rawtypes"})
  public static WorkerFunction.Sync<Map<String, Object>, Map<String, Object>> sequence(
      WorkerFunction<?, ?>... steps) {
    if (steps.length == 0) {
      throw new IllegalArgumentException("sequence requires at least one step");
    }
    WorkerFunction<?, ?>[] copy = steps.clone();
    return new WorkerFunction.Sync(
        Map.class,
        Map.class,
        (input, rawScope) -> {
          if (rawScope == null) {
            return WorkerResult.failed(
                "WorkerScope not available — "
                    + "sequence must run inside engine execution context");
          }
          var scope = (io.casehub.worker.api.WorkerScope) rawScope;
          var acc = (Map<String, Object>) input;
          for (var step : copy) {
            var result = scope.execute((WorkerFunction) step, acc);
            if (!(result.outcome() instanceof WorkerOutcome.Success)) {
              return result;
            }
            acc = merge(acc, (Map<String, Object>) result.output());
          }
          return WorkerResult.of(acc);
        });
  }

  @SuppressWarnings({"unchecked", "rawtypes"})
  public static <T> WorkerFunction.ExchangeProcessor<T, T> exchangeSequence(
      WorkerFunction.ExchangeProcessor<?, ?>... steps) {
    if (steps.length == 0) {
      throw new IllegalArgumentException("exchangeSequence requires at least one step");
    }
    for (int i = 0; i < steps.length - 1; i++) {
      if (!steps[i].bodyOutputType().equals(steps[i + 1].bodyInputType())) {
        throw new IllegalArgumentException(
            "Type mismatch at step "
                + i
                + " → "
                + (i + 1)
                + ": "
                + steps[i].bodyOutputType().getName()
                + " → "
                + steps[i + 1].bodyInputType().getName());
      }
    }
    WorkerFunction.ExchangeProcessor<?, ?>[] copy = steps.clone();
    Class<T> inType = (Class<T>) copy[0].bodyInputType();
    return new WorkerFunction.ExchangeProcessor(
        inType,
        inType,
        (java.util.function.BiFunction<
                io.casehub.worker.api.Exchange,
                io.casehub.worker.api.WorkerScope,
                WorkerResult<io.casehub.worker.api.Exchange>>)
            (exchange, scope) -> {
              io.casehub.worker.api.Exchange current = exchange;
              for (var step : copy) {
                @SuppressWarnings("unchecked")
                WorkerResult<io.casehub.worker.api.Exchange> result =
                    (WorkerResult<io.casehub.worker.api.Exchange>)
                        ((WorkerFunction.ExchangeProcessor) step).fn().apply(current, scope);
                if (!(result.outcome() instanceof io.casehub.worker.api.WorkerOutcome.Success)) {
                  return result;
                }
                current = result.output();
              }
              return WorkerResult.of(current);
            });
  }

  /**
   * Merges two maps, with overlay keys overwriting base keys.
   *
   * @param base the base map
   * @param overlay the overlay map
   * @return a new LinkedHashMap with base entries followed by overlay entries
   */
  public static Map<String, Object> merge(Map<String, Object> base, Map<String, Object> overlay) {
    var merged = new LinkedHashMap<>(base);
    merged.putAll(overlay);
    return merged;
  }
}
