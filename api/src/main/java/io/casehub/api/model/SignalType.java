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

import java.util.Map;
import java.util.Objects;

public record SignalType<T>(String name, Class<T> payloadType) {

  public SignalType {
    Objects.requireNonNull(name, "name");
    Objects.requireNonNull(payloadType, "payloadType");
  }

  public static <T> SignalType<T> of(String name, Class<T> payloadType) {
    return new SignalType<>(name, payloadType);
  }

  @SuppressWarnings("unchecked")
  public static SignalType<Map<String, Object>> untyped(String name) {
    return new SignalType<>(name, (Class<Map<String, Object>>) (Class<?>) Map.class);
  }
}
