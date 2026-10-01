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
package io.casehub.engine.runtime.improvement;

import io.casehub.api.model.improvement.ImprovementRequest;
import io.casehub.api.spi.improvement.ConflictStrategy;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@ApplicationScoped
public class FilePathConflictStrategy implements ConflictStrategy {

  @Override
  public String domainId() {
    return "code-evolution";
  }

  @Override
  public ConflictResult check(
      ImprovementRequest request,
      Map<UUID, ImprovementRequest> activeImprovements,
      int trivialThreshold) {
    List<String> requestPaths = CodeEvolutionMetadata.extractPaths(request);
    boolean isTrivial = request.estimatedSize() <= trivialThreshold && requestPaths.size() == 1;

    for (var entry : activeImprovements.entrySet()) {
      List<String> activePaths = CodeEvolutionMetadata.extractPaths(entry.getValue());
      for (String rp : requestPaths) {
        for (String ap : activePaths) {
          if (rp.equals(ap)) {
            return new ConflictResult.Conflicting(entry.getKey(), rp);
          }
          if (!isTrivial && sameDirectory(rp, ap)) {
            return new ConflictResult.Conflicting(entry.getKey(), rp);
          }
        }
      }
    }
    return new ConflictResult.Clear();
  }

  private boolean sameDirectory(String a, String b) {
    return parentDir(a).equals(parentDir(b));
  }

  private String parentDir(String path) {
    int last = path.lastIndexOf('/');
    return last > 0 ? path.substring(0, last) : "";
  }
}
