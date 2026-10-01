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
import io.casehub.api.model.stigmergy.ConductorInboxEntry;
import io.casehub.api.model.stigmergy.EscalationContext;
import io.casehub.api.model.stigmergy.EscalationPolicy;
import io.casehub.api.model.stigmergy.GateCheckpoint;
import io.casehub.api.model.stigmergy.GatePolicy;
import io.casehub.api.model.stigmergy.GatePolicy.GateMode;
import io.casehub.api.model.stigmergy.ResearchPipelineResult;
import io.casehub.api.model.stigmergy.ResearchScope;
import io.casehub.api.spi.improvement.EscalationProvider;
import io.casehub.api.spi.improvement.HypothesisFormer;
import io.casehub.api.spi.improvement.ResearchAnalyzer;
import io.casehub.api.spi.improvement.ResearchCorpus;
import io.casehub.api.spi.improvement.ResearchDepth;
import io.casehub.api.spi.improvement.ResearchScoper;
import io.casehub.api.spi.improvement.ResearchSearcher;
import jakarta.enterprise.context.ApplicationScoped;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@ApplicationScoped
public class ResearchPipelineOrchestrator {

  private final ResearchScoper scoper;
  private final ResearchSearcher searcher;
  private final ResearchAnalyzer analyzer;
  private final HypothesisFormer hypothesisFormer;
  private final ResearchCorpus corpus;
  private final ConductorInboxManager inboxManager;
  private final EscalationProvider escalationProvider;

  public ResearchPipelineOrchestrator(
      ResearchScoper scoper,
      ResearchSearcher searcher,
      ResearchAnalyzer analyzer,
      HypothesisFormer hypothesisFormer,
      ResearchCorpus corpus,
      ConductorInboxManager inboxManager,
      EscalationProvider escalationProvider) {
    this.scoper = scoper;
    this.searcher = searcher;
    this.analyzer = analyzer;
    this.hypothesisFormer = hypothesisFormer;
    this.corpus = corpus;
    this.inboxManager = inboxManager;
    this.escalationProvider = escalationProvider;
  }

  public ResearchPipelineResult execute(
      UUID caseId,
      String tenancyId,
      ResearchDepth depth,
      CapabilityAreaAssessment area,
      Map<String, String> driveContext,
      GatePolicy gatePolicy,
      EscalationPolicy escalationPolicy) {

    var scope = scoper.scope(depth, area, driveContext);

    var scopeOutcome =
        evaluateGate(
            gatePolicy.effectiveMode(CodeEvolutionStages.RESEARCH_SCOPE),
            caseId,
            tenancyId,
            CodeEvolutionStages.RESEARCH_SCOPE,
            escalationPolicy,
            buildContext(scope));

    if (scopeOutcome == GateOutcome.BLOCK) {
      var checkpoint = new GateCheckpoint.ScopeCheckpoint(scope);
      var entryId =
          enqueueForApproval(caseId, tenancyId, CodeEvolutionStages.RESEARCH_SCOPE, checkpoint);
      return new ResearchPipelineResult.AwaitingGate(
          CodeEvolutionStages.RESEARCH_SCOPE, entryId, checkpoint);
    }

    return executeFromScope(caseId, tenancyId, depth, area, driveContext, scope);
  }

  ResearchPipelineResult executeFromScope(
      UUID caseId,
      String tenancyId,
      ResearchDepth depth,
      CapabilityAreaAssessment area,
      Map<String, String> driveContext,
      ResearchScope scope) {

    var candidates = searcher.search(scope, depth);
    var analysis = analyzer.analyze(candidates, scope, depth);
    corpus.store(candidates, analysis);
    var hypotheses = hypothesisFormer.form(analysis, area);

    return new ResearchPipelineResult.Completed(hypotheses);
  }

  enum GateOutcome {
    BLOCK,
    NOTIFY,
    PROCEED
  }

  private GateOutcome evaluateGate(
      GateMode mode,
      UUID caseId,
      String tenancyId,
      String stage,
      EscalationPolicy policy,
      EscalationContext context) {
    if (mode == GateMode.GATED) {
      return GateOutcome.BLOCK;
    }
    var result =
        escalationProvider.evaluate(
            caseId,
            tenancyId,
            stage,
            context,
            policy,
            inboxManager.activeWatchPatterns(caseId, tenancyId));
    if (result.escalate()) {
      return GateOutcome.BLOCK;
    }
    return mode == GateMode.NOTIFY ? GateOutcome.NOTIFY : GateOutcome.PROCEED;
  }

  private EscalationContext buildContext(ResearchScope scope) {
    return new EscalationContext(null, null, null, null, null, Map.of());
  }

  private String enqueueForApproval(
      UUID caseId, String tenancyId, String stage, GateCheckpoint checkpoint) {
    var entryId = UUID.randomUUID().toString();
    var entry =
        new ConductorInboxEntry(
            caseId,
            entryId,
            stage,
            ConductorInboxEntry.Status.PENDING,
            null,
            null,
            null,
            "Gate checkpoint at " + stage,
            List.of(),
            1.0,
            Instant.now(),
            null,
            null,
            null);
    inboxManager.enqueue(caseId, entry, tenancyId);
    return entryId;
  }
}
