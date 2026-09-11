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
package io.casehub.engine.common.internal.jq;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;

public record ValidationResult(boolean ok, String error, List<JsonNode> output) {

  public static ValidationResult ok(List<JsonNode> out) {
    return new ValidationResult(true, null, out);
  }

  public static ValidationResult error(String msg) {
    return new ValidationResult(false, msg, null);
  }

  public boolean isTrue() {
    if (!ok || output == null || output.isEmpty()) {
      return false;
    }

    for (JsonNode node : output) {
      if (node.isBoolean() && node.asBoolean()) {
        return true;
      }
    }

    return false;
  }
}
