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

import io.casehub.api.model.stigmergy.CapabilityAreaAssessment;
import io.casehub.api.model.stigmergy.ImprovementHypothesis;
import io.casehub.api.spi.improvement.HypothesisFormer;
import io.casehub.api.spi.improvement.ResearchAnalyzer;
import io.casehub.api.spi.improvement.ResearchCorpus;
import io.casehub.api.spi.improvement.ResearchDepth;
import io.casehub.api.spi.improvement.ResearchScoper;
import io.casehub.api.spi.improvement.ResearchSearcher;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.List;
import java.util.Map;

@ApplicationScoped
public class ResearchPipelineOrchestrator {

  private final ResearchScoper scoper;
  private final ResearchSearcher searcher;
  private final ResearchAnalyzer analyzer;
  private final HypothesisFormer hypothesisFormer;
  private final ResearchCorpus corpus;

  public ResearchPipelineOrchestrator(
      ResearchScoper scoper,
      ResearchSearcher searcher,
      ResearchAnalyzer analyzer,
      HypothesisFormer hypothesisFormer,
      ResearchCorpus corpus) {
    this.scoper = scoper;
    this.searcher = searcher;
    this.analyzer = analyzer;
    this.hypothesisFormer = hypothesisFormer;
    this.corpus = corpus;
  }

  public List<ImprovementHypothesis> execute(
      ResearchDepth depth, CapabilityAreaAssessment area, Map<String, String> driveContext) {
    var scope = scoper.scope(depth, area, driveContext);
    var candidates = searcher.search(scope, depth);
    var analysis = analyzer.analyze(candidates, scope, depth);

    corpus.store(candidates, analysis);

    return hypothesisFormer.form(analysis, area);
  }
}
