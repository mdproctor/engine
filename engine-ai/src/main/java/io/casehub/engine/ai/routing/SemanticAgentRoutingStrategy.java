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
package io.casehub.engine.ai.routing;

import com.fasterxml.jackson.databind.JsonNode;
import io.casehub.api.spi.routing.AgentAssignment;
import io.casehub.api.spi.routing.AgentCandidate;
import io.casehub.api.spi.routing.AgentRoutingContext;
import io.casehub.api.spi.routing.AgentRoutingStrategy;
import io.casehub.eidos.api.AgentCapability;
import io.casehub.eidos.api.AgentDescriptor;
import io.casehub.engine.ai.spi.AgentEmbeddingProvider;
import io.casehub.engine.common.internal.jq.JQEvaluator;
import io.casehub.engine.common.internal.jq.ValidationResult;
import io.casehub.ledger.routing.TrustCandidateClassifier;
import io.casehub.ledger.routing.TrustCandidateClassifier.ClassifiedCandidate;
import io.casehub.ledger.routing.TrustCandidateClassifier.Phase;
import io.casehub.ledger.routing.TrustCandidateClassifier.ScoredCandidate;
import io.casehub.ledger.routing.TrustRoutingPolicy;
import io.casehub.ledger.routing.TrustRoutingPolicyProvider;
import io.casehub.ledger.routing.TrustScoreCache;
import io.smallrye.mutiny.Uni;
import io.smallrye.mutiny.infrastructure.Infrastructure;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Alternative;
import jakarta.inject.Inject;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/**
 * Semantic {@link AgentRoutingStrategy} that re-ranks trust-qualified candidates by embedding
 * similarity between the case context and each agent's vocabulary.
 *
 * <p>Trust filtering runs first (same phases as {@link
 * io.casehub.ledger.routing.TrustWeightedAgentStrategy}). Only QUALIFIED candidates are re-ranked
 * by semantic similarity. BORDERLINE candidates still trigger {@link
 * AgentAssignment.EscalateToOversight} when all candidates are borderline. BOOTSTRAP candidates
 * receive availability scoring with no semantic signal.
 *
 * <p>Activates at {@code @Priority(2)} — overrides {@code TrustWeightedAgentStrategy}
 * ({@code @Priority(1)}) when on the classpath.
 *
 * <h3>Final score formula (effective weights at defaults: semantic=0.4, blendFactor=0.6)</h3>
 *
 * <pre>
 *   trustBlend = trustScore × blendFactor + workload × (1-blendFactor)
 *   finalScore = semantic × semanticWeight + trustBlend × (1-semanticWeight)
 *   // defaults: semantic=0.40, trust=0.36, workload=0.24 — sums to 1.0
 * </pre>
 *
 * <p>Embedding calls run on the default worker pool (not the Vert.x IO thread). The returned {@code
 * Uni} completes on the worker pool after all embed calls finish.
 *
 * <p>Embedding caching tracked in engine#380.
 */
@Alternative
@Priority(2)
@ApplicationScoped
public class SemanticAgentRoutingStrategy implements AgentRoutingStrategy {

  private static final Logger LOG = Logger.getLogger(SemanticAgentRoutingStrategy.class);

  private final TrustCandidateClassifier classifier;
  private final TrustScoreCache cache;
  private final TrustRoutingPolicyProvider policyProvider;
  private final AgentEmbeddingProvider embeddingProvider;
  private final JQEvaluator jqEvaluator;
  private final double semanticWeight;
  private final String contextSummaryJq;

  @Inject
  public SemanticAgentRoutingStrategy(
      final TrustCandidateClassifier classifier,
      final TrustScoreCache cache,
      final TrustRoutingPolicyProvider policyProvider,
      final AgentEmbeddingProvider embeddingProvider,
      final JQEvaluator jqEvaluator,
      @ConfigProperty(name = "casehub.engine.ai.semantic-weight", defaultValue = "0.4")
          final double semanticWeight,
      @ConfigProperty(name = "casehub.engine.ai.context-summary-jq", defaultValue = ".")
          final String contextSummaryJq) {
    this.classifier = classifier;
    this.cache = cache;
    this.policyProvider = policyProvider;
    this.embeddingProvider = embeddingProvider;
    this.jqEvaluator = jqEvaluator;
    this.semanticWeight = semanticWeight;
    this.contextSummaryJq = contextSummaryJq;
  }

  @Override
  public Uni<AgentAssignment> select(
      final AgentRoutingContext context, final List<AgentCandidate> candidates) {
    if (candidates.isEmpty()) {
      return Uni.createFrom().item(AgentAssignment.unresolvable());
    }

    final TrustRoutingPolicy policy = policyProvider.forCapability(context.capabilityName());
    final List<ClassifiedCandidate> classified =
        classifier.classify(candidates, context.capabilityName(), policy, cache);

    return Uni.createFrom()
        .voidItem()
        .emitOn(Infrastructure.getDefaultWorkerPool())
        .map(
            ignored -> {
              final String queryText =
                  extractQueryText(context.caseContext(), context.capabilityName());
              final float[] queryVector = embeddingProvider.embed(queryText);

              final List<ScoredCandidate> scored = new ArrayList<>(classified.size());
              for (final ClassifiedCandidate cc : classified) {
                scored.add(new ScoredCandidate(cc, score(cc, queryVector, policy)));
              }

              return classifier.decide(classified, scored, context.capabilityName());
            });
  }

  private double score(
      final ClassifiedCandidate cc, final float[] queryVector, final TrustRoutingPolicy policy) {
    return switch (cc.phase()) {
      case Phase.BOOTSTRAP -> cc.workloadScore();
      case Phase.BORDERLINE, Phase.EXCLUDED_PHASE2B, Phase.EXCLUDED_PHASE3 -> 0.0;
      case Phase.QUALIFIED -> {
        if (cc.candidate().agentDescriptor() == null) {
          // No descriptor → treat as bootstrap (no semantic signal available)
          yield cc.workloadScore();
        }
        final float[] docVector =
            embeddingProvider.embed(buildVocabularyText(cc.candidate().agentDescriptor()));
        final double semantic = AgentEmbeddingProvider.cosineSimilarity(queryVector, docVector);
        final double trust = cc.trustScore().getAsDouble();
        final double trustBlend =
            trust * policy.blendFactor() + cc.workloadScore() * (1.0 - policy.blendFactor());
        yield semantic * semanticWeight + trustBlend * (1.0 - semanticWeight);
      }
    };
  }

  private String extractQueryText(final JsonNode caseContext, final String capabilityName) {
    if (caseContext == null || caseContext.isNull() || caseContext.isMissingNode()) {
      return capabilityName;
    }
    final ValidationResult result = jqEvaluator.eval(contextSummaryJq, caseContext);
    if (!result.ok() || result.output() == null || result.output().isEmpty()) {
      LOG.warnf(
          "caseContext JQ extraction failed for jq='%s'; falling back to capability name '%s'",
          contextSummaryJq, capabilityName);
      return capabilityName;
    }
    final String extracted =
        result.output().stream()
            .map(JsonNode::asText)
            .filter(s -> !s.isBlank())
            .collect(Collectors.joining(" "));
    return extracted.isBlank() ? capabilityName : extracted;
  }

  private String buildVocabularyText(final AgentDescriptor descriptor) {
    final StringBuilder sb = new StringBuilder();
    appendIfNonBlank(sb, descriptor.domainVocabulary());
    appendIfNonBlank(sb, descriptor.slotVocabulary());
    appendIfNonBlank(sb, descriptor.dispositionVocabulary());
    if (descriptor.capabilities() != null) {
      for (final AgentCapability cap : descriptor.capabilities()) {
        sb.append("\ncapability:").append(cap.name());
        if (cap.tags() != null && !cap.tags().isEmpty()) {
          sb.append(" tags:").append(String.join(" ", cap.tags()));
        }
        if (cap.epistemicDomains() != null && !cap.epistemicDomains().isEmpty()) {
          sb.append(" domains:").append(String.join(" ", cap.epistemicDomains().keySet()));
        }
      }
    }
    return sb.toString().trim();
  }

  private static void appendIfNonBlank(final StringBuilder sb, final String value) {
    if (value != null && !value.isBlank()) {
      sb.append(value).append(' ');
    }
  }
}
