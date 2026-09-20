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

import io.casehub.api.model.stigmergy.ImprovementRequest;
import io.casehub.engine.common.spi.Resettable;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.Map;
import java.util.UUID;

@ApplicationScoped
public class ConflictDetector implements Resettable {

  public sealed interface ConflictCheck permits ConflictCheck.Clear, ConflictCheck.Conflicting {
    record Clear() implements ConflictCheck {}

    record Conflicting(UUID blockingImprovementId, String conflictPath) implements ConflictCheck {}
  }

  public ConflictCheck check(
      ImprovementRequest request,
      Map<UUID, ImprovementRequest> activeImprovements,
      int trivialThreshold) {
    boolean isTrivial =
        request.estimatedSize() <= trivialThreshold && request.targetPaths().size() == 1;

    for (var entry : activeImprovements.entrySet()) {
      var active = entry.getValue();
      for (String requestPath : request.targetPaths()) {
        for (String activePath : active.targetPaths()) {
          if (requestPath.equals(activePath)) {
            return new ConflictCheck.Conflicting(entry.getKey(), requestPath);
          }
          if (!isTrivial && sameDirectory(requestPath, activePath)) {
            return new ConflictCheck.Conflicting(entry.getKey(), requestPath);
          }
        }
      }
    }
    return new ConflictCheck.Clear();
  }

  private boolean sameDirectory(String a, String b) {
    return parentDir(a).equals(parentDir(b));
  }

  private String parentDir(String path) {
    int last = path.lastIndexOf('/');
    return last > 0 ? path.substring(0, last) : "";
  }

  @Override
  public void reset() {}
}
