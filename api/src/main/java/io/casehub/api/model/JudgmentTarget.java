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
package io.casehub.api.model;

import io.casehub.api.model.evaluator.JQExpressionEvaluator;
import io.casehub.api.spi.QuorumConfig;
import io.casehub.api.spi.routing.CandidateSetSpec;
import io.casehub.api.spi.routing.CandidateSetStrategy;
import io.casehub.api.spi.routing.StaticSetStrategy;
import io.casehub.platform.api.expression.ExpressionEvaluator;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;

public final class JudgmentTarget implements BindingTarget {

  private final String prompt;
  private final ExpressionEvaluator inputMapping;
  private final ExpressionEvaluator outputMapping;
  private final Class<?> resolutionType;
  private final Duration expiresIn;
  private final ExpressionEvaluator expiresInExpression;
  private final ExpressionEvaluator expiresAtExpression;
  private final VerificationMode verificationMode;
  private final String verifierStrategy;
  private final String escalatorStrategy;
  private final String trustPolicy;
  private final List<EvidenceRequirement> evidenceRequirements;
  private final CallerConfig callerConfig;

  private JudgmentTarget(Builder builder) {
    this.prompt = builder.prompt;
    this.inputMapping = builder.inputMapping;
    this.outputMapping = builder.outputMapping;
    this.resolutionType = builder.resolutionType;
    this.expiresIn = builder.expiresIn;
    this.expiresInExpression = builder.expiresInExpression;
    this.expiresAtExpression = builder.expiresAtExpression;
    this.verificationMode =
        builder.verificationMode != null ? builder.verificationMode : VerificationMode.SYNCHRONOUS;
    this.verifierStrategy = builder.verifierStrategy;
    this.escalatorStrategy = builder.escalatorStrategy;
    this.trustPolicy = builder.trustPolicy;
    this.evidenceRequirements = List.copyOf(builder.evidenceRequirements);
    this.callerConfig = Objects.requireNonNull(builder.callerConfig, "callerConfig required");
  }

  public static Builder builder() {
    return new Builder(new CallerConfig.Any());
  }

  public static Builder forHuman() {
    return new Builder(null).callerType(CallerType.HUMAN);
  }

  public static Builder forLlm() {
    return new Builder(null).callerType(CallerType.LLM);
  }

  public static Builder forA2A() {
    return new Builder(null).callerType(CallerType.A2A);
  }

  public static Builder forAny() {
    return new Builder(new CallerConfig.Any());
  }

  public String prompt() {
    return prompt;
  }

  public ExpressionEvaluator inputMapping() {
    return inputMapping;
  }

  public ExpressionEvaluator outputMapping() {
    return outputMapping;
  }

  public Class<?> resolutionType() {
    return resolutionType;
  }

  public Duration expiresIn() {
    return expiresIn;
  }

  public ExpressionEvaluator expiresInExpression() {
    return expiresInExpression;
  }

  public ExpressionEvaluator expiresAtExpression() {
    return expiresAtExpression;
  }

  public VerificationMode verificationMode() {
    return verificationMode;
  }

  public String verifierStrategy() {
    return verifierStrategy;
  }

  public String escalatorStrategy() {
    return escalatorStrategy;
  }

  public String trustPolicy() {
    return trustPolicy;
  }

  public List<EvidenceRequirement> evidenceRequirements() {
    return evidenceRequirements;
  }

  public CallerConfig callerConfig() {
    return callerConfig;
  }

  private enum CallerType {
    HUMAN,
    LLM,
    A2A
  }

  public static final class Builder {

    private String prompt;
    private ExpressionEvaluator inputMapping;
    private ExpressionEvaluator outputMapping;
    private Class<?> resolutionType;
    private Duration expiresIn;
    private ExpressionEvaluator expiresInExpression;
    private ExpressionEvaluator expiresAtExpression;
    private VerificationMode verificationMode;
    private String verifierStrategy;
    private String escalatorStrategy;
    private String trustPolicy;
    private final List<EvidenceRequirement> evidenceRequirements = new ArrayList<>();
    private CallerConfig callerConfig;
    private CallerType callerType;

    // Human-specific builder state
    private CandidateSetSpec candidateGroups;
    private CandidateSetSpec candidateUsers;
    private String title;
    private ExpressionEvaluator titleExpression;
    private Set<String> outcomes;
    private Integer claimDeadlineHours;
    private String scope;
    private ExpressionEvaluator scopeExpression;
    private String priority;
    private String templateRef;
    private Class<?> payloadType;
    private QuorumConfig quorum;

    // LLM-specific builder state
    private String model;
    private String modelName;
    private String systemPrompt;

    // A2A-specific builder state
    private String endpoint;
    private String skill;
    private boolean streaming;

    private Builder(CallerConfig callerConfig) {
      this.callerConfig = callerConfig;
    }

    private Builder callerType(CallerType type) {
      this.callerType = type;
      return this;
    }

    public Builder prompt(String prompt) {
      this.prompt = prompt;
      return this;
    }

    public Builder inputMapping(String jqExpression) {
      this.inputMapping = new JQExpressionEvaluator(jqExpression);
      return this;
    }

    public Builder inputMapping(ExpressionEvaluator evaluator) {
      this.inputMapping = evaluator;
      return this;
    }

    public Builder outputMapping(String jqExpression) {
      this.outputMapping = new JQExpressionEvaluator(jqExpression);
      return this;
    }

    public Builder outputMapping(ExpressionEvaluator evaluator) {
      this.outputMapping = evaluator;
      return this;
    }

    public Builder resolutionType(Class<?> resolutionType) {
      this.resolutionType = resolutionType;
      return this;
    }

    public Builder expiresIn(Duration expiresIn) {
      this.expiresIn = expiresIn;
      return this;
    }

    public Builder expiresInExpression(String jqExpression) {
      this.expiresInExpression = new JQExpressionEvaluator(jqExpression);
      return this;
    }

    public Builder expiresInExpression(ExpressionEvaluator evaluator) {
      this.expiresInExpression = evaluator;
      return this;
    }

    public Builder expiresAtExpression(String expression) {
      this.expiresAtExpression = new JQExpressionEvaluator(expression);
      return this;
    }

    public Builder expiresAtExpression(ExpressionEvaluator evaluator) {
      this.expiresAtExpression = evaluator;
      return this;
    }

    public Builder verificationMode(VerificationMode verificationMode) {
      this.verificationMode = verificationMode;
      return this;
    }

    public Builder verifier(String verifierStrategy) {
      this.verifierStrategy = verifierStrategy;
      return this;
    }

    public Builder escalator(String escalatorStrategy) {
      this.escalatorStrategy = escalatorStrategy;
      return this;
    }

    public Builder trustPolicy(String trustPolicy) {
      this.trustPolicy = trustPolicy;
      return this;
    }

    public Builder evidence(String name, EvidenceType type, boolean required) {
      this.evidenceRequirements.add(new EvidenceRequirement(name, type, required));
      return this;
    }

    public Builder callerConfig(CallerConfig callerConfig) {
      this.callerConfig = callerConfig;
      this.callerType = null;
      return this;
    }

    // Human-specific convenience methods

    public Builder candidateGroups(Set<String> groups) {
      this.candidateGroups = new CandidateSetSpec.Inline(StaticSetStrategy.of(groups));
      return this;
    }

    public Builder candidateGroups(CandidateSetStrategy strategy) {
      this.candidateGroups = new CandidateSetSpec.Inline(strategy);
      return this;
    }

    public Builder candidateGroups(CandidateSetSpec spec) {
      this.candidateGroups = spec;
      return this;
    }

    public Builder candidateUsers(Set<String> users) {
      this.candidateUsers = new CandidateSetSpec.Inline(StaticSetStrategy.of(users));
      return this;
    }

    public Builder candidateUsers(CandidateSetSpec spec) {
      this.candidateUsers = spec;
      return this;
    }

    public Builder title(String title) {
      this.title = title;
      return this;
    }

    public Builder titleExpression(String jqExpression) {
      this.titleExpression = new JQExpressionEvaluator(jqExpression);
      return this;
    }

    public Builder titleExpression(ExpressionEvaluator evaluator) {
      this.titleExpression = evaluator;
      return this;
    }

    public Builder outcomes(Set<String> outcomes) {
      this.outcomes = outcomes;
      return this;
    }

    public Builder claimDeadlineHours(Integer hours) {
      this.claimDeadlineHours = hours;
      return this;
    }

    public Builder scope(String scope) {
      this.scope = scope;
      return this;
    }

    public Builder scopeExpression(String jqExpression) {
      this.scopeExpression = new JQExpressionEvaluator(jqExpression);
      return this;
    }

    public Builder scopeExpression(ExpressionEvaluator evaluator) {
      this.scopeExpression = evaluator;
      return this;
    }

    public Builder priority(String priority) {
      this.priority = priority;
      return this;
    }

    public Builder templateRef(String templateRef) {
      this.templateRef = templateRef;
      return this;
    }

    public Builder payloadType(Class<?> payloadType) {
      this.payloadType = payloadType;
      return this;
    }

    public Builder quorum(QuorumConfig quorum) {
      this.quorum = quorum;
      return this;
    }

    // LLM-specific convenience methods

    public Builder model(String model) {
      this.model = model;
      return this;
    }

    public Builder modelName(String modelName) {
      this.modelName = modelName;
      return this;
    }

    public Builder systemPrompt(String systemPrompt) {
      this.systemPrompt = systemPrompt;
      return this;
    }

    // A2A-specific convenience methods

    public Builder endpoint(String endpoint) {
      this.endpoint = endpoint;
      return this;
    }

    public Builder skill(String skill) {
      this.skill = skill;
      return this;
    }

    public Builder streaming(boolean streaming) {
      this.streaming = streaming;
      return this;
    }

    public JudgmentTarget build() {
      if (callerType != null) {
        this.callerConfig = buildCallerConfig();
      }
      return new JudgmentTarget(this);
    }

    private CallerConfig buildCallerConfig() {
      return switch (callerType) {
        case HUMAN ->
            new CallerConfig.Human(
                candidateGroups,
                candidateUsers,
                title,
                titleExpression,
                outcomes,
                claimDeadlineHours,
                scope,
                scopeExpression,
                priority,
                templateRef,
                payloadType,
                quorum);
        case LLM -> new CallerConfig.Llm(model, modelName, systemPrompt);
        case A2A -> new CallerConfig.A2A(endpoint, skill, streaming);
      };
    }
  }
}
