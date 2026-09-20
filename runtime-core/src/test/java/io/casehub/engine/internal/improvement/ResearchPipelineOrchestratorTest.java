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

import static org.assertj.core.api.Assertions.assertThat;

import io.casehub.api.model.stigmergy.CapabilityAreaAssessment;
import io.casehub.api.spi.improvement.ResearchDepth;
import io.casehub.engine.internal.improvement.research.DefaultHypothesisFormer;
import io.casehub.engine.internal.improvement.research.DefaultResearchAnalyzer;
import io.casehub.engine.internal.improvement.research.DefaultResearchScoper;
import io.casehub.engine.internal.improvement.research.DefaultResearchSearcher;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ResearchPipelineOrchestratorTest {

  @Test
  void pipelineExecutesAllSteps() {
    var corpus = new InMemoryResearchCorpus();
    var orchestrator =
        new ResearchPipelineOrchestrator(
            new DefaultResearchScoper(),
            new DefaultResearchSearcher(),
            new DefaultResearchAnalyzer(),
            new DefaultHypothesisFormer(),
            corpus);

    var area =
        new CapabilityAreaAssessment(
            "stability",
            0.4,
            CapabilityAreaAssessment.LandscapePosition.BEHIND,
            0.8,
            0.3,
            2.67,
            Instant.now());

    var hypotheses = orchestrator.execute(ResearchDepth.HORIZON_SCAN, area, Map.of());

    assertThat(hypotheses).isNotNull();
  }
}
