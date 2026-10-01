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
package io.casehub.engine.internal.improvement;

import io.casehub.api.model.improvement.ImprovementRequest;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class CodeEvolutionMetadata {

  public static final String TARGET_PATHS = "target-paths";
  public static final String TARGET_REPO = "target-repo";

  public static List<String> extractPaths(ImprovementRequest request) {
    String paths = request.metadata().getOrDefault(TARGET_PATHS, "");
    return paths.isEmpty() ? List.of() : List.of(paths.split(","));
  }

  public static String extractRepo(ImprovementRequest request) {
    return request.metadata().getOrDefault(TARGET_REPO, null);
  }

  public static Map<String, String> encode(String repo, List<String> paths) {
    var map = new HashMap<String, String>();
    if (repo != null) map.put(TARGET_REPO, repo);
    if (paths != null && !paths.isEmpty()) map.put(TARGET_PATHS, String.join(",", paths));
    return Map.copyOf(map);
  }

  private CodeEvolutionMetadata() {}
}
