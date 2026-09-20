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
import java.util.Map;

@ApplicationScoped
public class CoverageGapWorker {

  public String category() {
    return "coverage-gap";
  }

  public IntrospectionResult introspect(ImprovementRequest request) {
    return new IntrospectionResult(
        category(),
        "Coverage gap: " + request.target(),
        request.targetPaths(),
        request.estimatedSize() > 0 ? request.estimatedSize() : 50,
        "Generate test skeletons for uncovered code in " + request.target(),
        Map.of("source", "coverage-report"));
  }
}
