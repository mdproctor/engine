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

import java.util.Objects;

public record ChannelDeclaration(
    String name, Class<?> recordType, String transport, LifecycleScope scope) {
  public ChannelDeclaration {
    Objects.requireNonNull(name, "name must not be null");
    Objects.requireNonNull(recordType, "recordType must not be null");
    if (name.isBlank()) throw new IllegalArgumentException("name must not be blank");
    if (transport == null) transport = "in-memory";
    if (scope == null) scope = LifecycleScope.CASE;
    if (scope == LifecycleScope.BINDING) {
      throw new IllegalArgumentException(
          "BINDING scope is not valid for channels — channels must outlive a single binding execution. Use COMPOUND or CASE.");
    }
  }
}
