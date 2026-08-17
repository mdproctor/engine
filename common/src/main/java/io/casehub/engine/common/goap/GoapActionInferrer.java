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
package io.casehub.engine.common.goap;

import io.casehub.engine.plan.goap.GoapAction;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class GoapActionInferrer {

  private static final Set<Class<?>> INPUT_PARAMETER_TYPES =
      Set.of(
          String.class,
          int.class,
          Integer.class,
          long.class,
          Long.class,
          double.class,
          Double.class,
          float.class,
          Float.class,
          boolean.class,
          Boolean.class,
          byte.class,
          Byte.class,
          short.class,
          Short.class,
          char.class,
          Character.class,
          Map.class);

  private static final Set<String> INPUT_PARAMETER_TYPE_NAMES =
      Set.of("io.casehub.worker.api.WorkerScope");

  private GoapActionInferrer() {}

  public static boolean isInputParameter(Class<?> type) {
    return type.isPrimitive()
        || INPUT_PARAMETER_TYPES.contains(type)
        || INPUT_PARAMETER_TYPE_NAMES.contains(type.getName());
  }

  public static GoapAction infer(
      String name,
      List<Class<?>> inputTypes,
      Class<?> outputType,
      double cost,
      double benefit,
      Set<Class<?>> softDependencyTypes) {

    Map<String, Boolean> preconditions = new HashMap<>();
    Map<String, Boolean> softPreconditions = new HashMap<>();

    for (Class<?> inputType : inputTypes) {
      if (isInputParameter(inputType)) continue;
      String key = GoapKeyConvention.keyFor(inputType.getSimpleName());
      if (softDependencyTypes.contains(inputType)) {
        softPreconditions.put(key, true);
      } else {
        preconditions.put(key, true);
      }
    }

    Map<String, Boolean> effects = new HashMap<>();
    if (outputType != null && outputType != void.class && outputType != Void.class) {
      String effectKey = GoapKeyConvention.keyFor(outputType.getSimpleName());
      effects.put(effectKey, true);
    }

    return new GoapAction(name, preconditions, effects, cost, benefit, softPreconditions);
  }
}
