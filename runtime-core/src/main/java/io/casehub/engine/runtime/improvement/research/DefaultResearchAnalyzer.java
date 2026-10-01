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
package io.casehub.engine.internal.improvement.research;

import io.casehub.api.model.stigmergy.ResearchAnalysis;
import io.casehub.api.model.stigmergy.ResearchCandidate;
import io.casehub.api.model.stigmergy.ResearchFinding;
import io.casehub.api.model.stigmergy.ResearchScope;
import io.casehub.api.spi.improvement.ResearchAnalyzer;
import io.casehub.api.spi.improvement.ResearchDepth;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.List;

@ApplicationScoped
public class DefaultResearchAnalyzer implements ResearchAnalyzer {

  @Override
  public ResearchAnalysis analyze(
      List<ResearchCandidate> candidates, ResearchScope scope, ResearchDepth depth) {
    var findings =
        candidates.stream()
            .map(
                c ->
                    new ResearchFinding(
                        c.title(),
                        c.abstractText(),
                        "",
                        "",
                        "unknown",
                        scope.area().areaId(),
                        c.sourceUrl()))
            .toList();
    return new ResearchAnalysis(findings, List.of(), List.of(), List.of());
  }
}
