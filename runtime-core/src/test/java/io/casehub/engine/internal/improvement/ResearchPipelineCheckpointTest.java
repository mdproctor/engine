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

import io.casehub.api.model.improvement.ImprovementHypothesis;
import io.casehub.api.model.stigmergy.CapabilityAreaAssessment;
import io.casehub.api.model.stigmergy.EscalationPolicy;
import io.casehub.api.model.stigmergy.GatePolicy;
import io.casehub.api.model.stigmergy.GatePolicy.GateMode;
import io.casehub.api.model.stigmergy.ResearchPipelineResult;
import io.casehub.api.model.stigmergy.ResearchScope;
import io.casehub.api.spi.improvement.ResearchDepth;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ResearchPipelineCheckpointTest {

  private ResearchPipelineOrchestrator orchestrator;
  private UUID caseId;

  @BeforeEach
  void setUp() {
    var scoper = new StubResearchScoper();
    var searcher = new StubResearchSearcher();
    var analyzer = new StubResearchAnalyzer();
    var hypothesisFormer = new StubHypothesisFormer();
    var corpus = new InMemoryResearchCorpus();
    var inboxManager =
        new ConductorInboxManager(
            new InMemoryConductorInboxRepository(), new InMemoryWatchPatternStore());
    var escalationProvider = new DefaultEscalationProvider();

    orchestrator =
        new ResearchPipelineOrchestrator(
            scoper, searcher, analyzer, hypothesisFormer, corpus, inboxManager, escalationProvider);
    caseId = UUID.randomUUID();
  }

  @Test
  void gatedScopeReturnsAwaitingGate() {
    var gatePolicy =
        new GatePolicy(Map.of(CodeEvolutionStages.RESEARCH_SCOPE, GateMode.GATED), null);
    var escalationPolicy = new EscalationPolicy(null, null);
    var area = makeArea();

    var result =
        orchestrator.execute(
            caseId, "t1", ResearchDepth.HORIZON_SCAN, area, Map.of(), gatePolicy, escalationPolicy);

    assertThat(result).isInstanceOf(ResearchPipelineResult.AwaitingGate.class);
    var awaiting = (ResearchPipelineResult.AwaitingGate) result;
    assertThat(awaiting.stage()).isEqualTo(CodeEvolutionStages.RESEARCH_SCOPE);
    assertThat(awaiting.inboxEntryId()).isNotNull();
  }

  @Test
  void autoModeCompletesWithoutBlocking() {
    var gatePolicy = new GatePolicy(null, null);
    var escalationPolicy = new EscalationPolicy(null, null);
    var area = makeArea();

    var result =
        orchestrator.execute(
            caseId, "t1", ResearchDepth.HORIZON_SCAN, area, Map.of(), gatePolicy, escalationPolicy);

    assertThat(result).isInstanceOf(ResearchPipelineResult.Completed.class);
  }

  @Test
  void completedResultContainsHypotheses() {
    var gatePolicy = new GatePolicy(null, null);
    var escalationPolicy = new EscalationPolicy(null, null);
    var area = makeArea();

    var result =
        orchestrator.execute(
            caseId, "t1", ResearchDepth.HORIZON_SCAN, area, Map.of(), gatePolicy, escalationPolicy);

    assertThat(result).isInstanceOf(ResearchPipelineResult.Completed.class);
    var completed = (ResearchPipelineResult.Completed) result;
    assertThat(completed.hypotheses()).isNotNull();
  }

  private static CapabilityAreaAssessment makeArea() {
    return new CapabilityAreaAssessment(
        "test-area",
        0.5,
        CapabilityAreaAssessment.LandscapePosition.AT_PARITY,
        0.5,
        0.5,
        1.0,
        Instant.now());
  }

  private static class StubResearchScoper implements io.casehub.api.spi.improvement.ResearchScoper {
    @Override
    public ResearchScope scope(
        ResearchDepth depth, CapabilityAreaAssessment area, Map<String, String> driveContext) {
      return new ResearchScope("test question", List.of("test"), List.of("internal"), area);
    }
  }

  private static class StubResearchSearcher
      implements io.casehub.api.spi.improvement.ResearchSearcher {
    @Override
    public List<io.casehub.api.model.stigmergy.ResearchCandidate> search(
        ResearchScope scope, ResearchDepth depth) {
      return List.of();
    }
  }

  private static class StubResearchAnalyzer
      implements io.casehub.api.spi.improvement.ResearchAnalyzer {
    @Override
    public io.casehub.api.model.stigmergy.ResearchAnalysis analyze(
        List<io.casehub.api.model.stigmergy.ResearchCandidate> candidates,
        ResearchScope scope,
        ResearchDepth depth) {
      return new io.casehub.api.model.stigmergy.ResearchAnalysis(
          List.of(), List.of(), List.of(), List.of());
    }
  }

  private static class StubHypothesisFormer
      implements io.casehub.api.spi.improvement.HypothesisFormer {
    @Override
    public List<ImprovementHypothesis> form(
        io.casehub.api.model.stigmergy.ResearchAnalysis analysis, CapabilityAreaAssessment area) {
      return List.of();
    }
  }
}
