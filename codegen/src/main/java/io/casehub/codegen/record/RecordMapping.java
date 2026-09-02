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
package io.casehub.codegen.record;

import java.util.List;
import java.util.Map;

public record RecordMapping(
    String packageName,
    List<String> skipPatterns,
    Map<String, String> imports,
    Map<String, String> deserializers,
    Map<String, TypeMapping> types) {

  public RecordMapping {
    if (skipPatterns == null) skipPatterns = List.of();
    if (imports == null) imports = Map.of();
    if (deserializers == null) deserializers = Map.of();
    if (types == null) types = Map.of();
  }
}
