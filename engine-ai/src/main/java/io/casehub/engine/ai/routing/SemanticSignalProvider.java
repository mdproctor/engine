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
import io.casehub.api.spi.routing.AgentCandidate;
import io.casehub.api.spi.routing.AgentRoutingContext;
import io.casehub.api.spi.routing.RoutingSignal;
import io.casehub.api.spi.routing.RoutingSignalProvider;
import io.casehub.eidos.api.AgentCapability;
import io.casehub.eidos.api.AgentDescriptor;
import io.casehub.engine.ai.spi.AgentEmbeddingProvider;
import io.casehub.engine.common.internal.jq.JQEvaluator;
import io.casehub.engine.common.internal.jq.ValidationResult;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.stream.Collectors;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;
import org.jspecify.annotations.Nullable;

/**
 * Semantic similarity signal provider. Scores candidates by embedding cosine similarity between the
 * case context and the agent's vocabulary/capability description.
 */
@ApplicationScoped
public class SemanticSignalProvider implements RoutingSignalProvider {

  private static final Logger LOG = Logger.getLogger(SemanticSignalProvider.class);

  private final AgentEmbeddingProvider embeddingProvider;
  private final EmbeddingCache embeddingCache;
  private final JQEvaluator jqEvaluator;
  private final String contextSummaryJq;

  @Inject
  public SemanticSignalProvider(
      AgentEmbeddingProvider embeddingProvider,
      EmbeddingCache embeddingCache,
      JQEvaluator jqEvaluator,
      @ConfigProperty(name = "casehub.routing.semantic.context-jq", defaultValue = "tostring")
          String contextSummaryJq) {
    this.embeddingProvider = embeddingProvider;
    this.embeddingCache = embeddingCache;
    this.jqEvaluator = jqEvaluator;
    this.contextSummaryJq = contextSummaryJq;
  }

  @Override
  public String id() {
    return "semantic";
  }

  @Override
  public @Nullable RoutingSignal evaluate(
      AgentRoutingContext context, List<AgentCandidate> eligible) {
    String queryText = extractQueryText(context.caseContext(), context.capabilityName());
    float[] queryVector;
    try {
      queryVector = embeddingCache.getOrCompute(queryText, embeddingProvider);
    } catch (Exception e) {
      LOG.warnf(e, "Embedding service unavailable — semantic signal abstaining");
      return null;
    }

    var signals = new LinkedHashMap<String, RoutingSignal.CandidateSignal>();
    for (var candidate : eligible) {
      if (candidate.agentDescriptor() == null) {
        continue;
      }
      String docText = buildVocabularyText(candidate.agentDescriptor());
      float[] docVector = embeddingCache.getOrCompute(docText, embeddingProvider);
      double similarity = AgentEmbeddingProvider.cosineSimilarity(queryVector, docVector);
      double clamped = Math.max(0.0, Math.min(1.0, similarity));
      signals.put(
          candidate.workerId(),
          new RoutingSignal.CandidateSignal.Score(clamped, "semantic %.3f".formatted(clamped)));
    }

    return signals.isEmpty() ? null : new RoutingSignal(signals);
  }

  private String extractQueryText(JsonNode caseContext, String capabilityName) {
    if (caseContext == null || caseContext.isNull() || caseContext.isMissingNode()) {
      return capabilityName;
    }
    ValidationResult result = jqEvaluator.eval(contextSummaryJq, caseContext);
    if (!result.ok() || result.output() == null || result.output().isEmpty()) {
      return capabilityName;
    }
    String extracted =
        result.output().stream()
            .map(JsonNode::asText)
            .filter(s -> !s.isBlank())
            .collect(Collectors.joining(" "));
    return extracted.isBlank() ? capabilityName : extracted;
  }

  private String buildVocabularyText(AgentDescriptor descriptor) {
    StringBuilder sb = new StringBuilder();
    appendIfNonBlank(sb, descriptor.domainVocabulary());
    appendIfNonBlank(sb, descriptor.slotVocabulary());
    appendIfNonBlank(sb, descriptor.dispositionVocabulary());
    if (descriptor.capabilities() != null) {
      for (AgentCapability cap : descriptor.capabilities()) {
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

  private static void appendIfNonBlank(StringBuilder sb, String value) {
    if (value != null && !value.isBlank()) {
      sb.append(value).append(' ');
    }
  }
}
