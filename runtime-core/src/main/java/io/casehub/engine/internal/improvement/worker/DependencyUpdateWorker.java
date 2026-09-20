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
package io.casehub.engine.internal.improvement.worker;

import io.casehub.api.model.stigmergy.ImprovementRequest;
import io.casehub.api.model.stigmergy.IntrospectionResult;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.List;
import java.util.Map;

@ApplicationScoped
public class DependencyUpdateWorker {

  public String category() {
    return "dependency-update";
  }

  public IntrospectionResult introspect(ImprovementRequest request) {
    return new IntrospectionResult(
        category(),
        "Dependency update: " + request.target(),
        request.targetPaths().isEmpty() ? List.of("pom.xml") : request.targetPaths(),
        request.estimatedSize() > 0 ? request.estimatedSize() : 30,
        "Update " + request.target() + " to latest version",
        Map.of("source", "dependency-staleness-check"));
  }
}
