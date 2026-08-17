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

import java.util.Map;

public final class GoapKeyConvention {

  private GoapKeyConvention() {}

  public static String keyFor(String simpleTypeName) {
    if (simpleTypeName == null || simpleTypeName.isEmpty()) {
      throw new IllegalArgumentException("Type name must not be null or empty");
    }
    return Character.toLowerCase(simpleTypeName.charAt(0)) + simpleTypeName.substring(1);
  }

  public static String keyForParameterized(String containerName, String elementName) {
    String elementKey = keyFor(elementName);
    String containerLower = containerName.toLowerCase();
    return elementKey
        + Character.toUpperCase(containerLower.charAt(0))
        + containerLower.substring(1);
  }

  public static String detectCollision(
      String key, String newProducer, Map<String, String> existingKeyToProducer) {
    String existing = existingKeyToProducer.get(key);
    if (existing != null && !existing.equals(newProducer)) {
      return "Workers '"
          + existing
          + "' and '"
          + newProducer
          + "' both produce key '"
          + key
          + "' — add @Effect to disambiguate";
    }
    return null;
  }
}
