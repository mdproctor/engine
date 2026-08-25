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

import com.fasterxml.jackson.annotation.JsonProperty;
import io.casehub.api.model.evaluator.JQExpressionEvaluator;
import io.casehub.platform.api.acl.WorkerAction;
import io.casehub.platform.api.expression.ExpressionEvaluator;
import io.casehub.worker.api.Capability;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public class Binding {

  @JsonProperty("target")
  private final BindingTarget target;

  @com.fasterxml.jackson.annotation.JsonPropertyDescription(
      "Binding name — unique within the definition.")
  private final String name;

  @com.fasterxml.jackson.annotation.JsonPropertyDescription(
      "Trigger condition that activates this binding.")
  private final Trigger on;

  @com.fasterxml.jackson.annotation.JsonPropertyDescription(
      "Additional guard condition evaluated after the trigger fires.")
  private ExpressionEvaluator when;

  @com.fasterxml.jackson.annotation.JsonPropertyDescription(
      "Conflict resolution strategy for output merging. Default: LAST_WRITER_WINS.")
  private String conflictResolverStrategy;

  @com.fasterxml.jackson.annotation.JsonPropertyDescription(
      "How to handle worker decline, failure, and expiration outcomes.")
  private OutcomePolicy outcomePolicy;

  @com.fasterxml.jackson.annotation.JsonPropertyDescription(
      "Override the capability's input projection for this specific binding.")
  private ExpressionEvaluator inputProjectionOverride;

  @com.fasterxml.jackson.annotation.JsonPropertyDescription(
      "Context keys applied before dispatch — prevents infinite re-evaluation loops.")
  private Map<String, Object> contextWrite;

  @com.fasterxml.jackson.annotation.JsonPropertyDescription(
      "Context keys this binding is expected to produce — used for expectation validation.")
  private Set<String> producedKeys;

  @com.fasterxml.jackson.annotation.JsonPropertyDescription(
      "Worker lifetime scope: BINDING (single dispatch), COMPOUND, or CASE.")
  private LifecycleScope lifecycleScope;

  @com.fasterxml.jackson.annotation.JsonPropertyDescription(
      "PARTICIPANT blocks completion; COMPANION is a sidecar excluded from completion.")
  private Participation participation;

  @com.fasterxml.jackson.annotation.JsonPropertyDescription(
      "TRANSIENT (fire-and-forget), PERSISTENT (long-running), or REINVOKED (re-invoked with state).")
  private ExecutionMode executionMode;

  @com.fasterxml.jackson.annotation.JsonPropertyDescription(
      "Declared worker actions for rights classification.")
  private List<WorkerAction> permissionIntent;

  @com.fasterxml.jackson.annotation.JsonPropertyDescription(
      "Exchange projection strategy ID or JQ expression.")
  private String exchangeProjectionStrategy;

  private String exchangeProjectionExpression;

  @com.fasterxml.jackson.annotation.JsonPropertyDescription(
      "Output channel name for Exchange data flow.")
  private String produces;

  @com.fasterxml.jackson.annotation.JsonPropertyDescription(
      "Input channel name for Exchange data flow.")
  private String consumes;

  @com.fasterxml.jackson.annotation.JsonPropertyDescription(
      "Per-binding recovery override configuration.")
  private RecoveryOverride recoveryOverride;

  private SideEffectClassification sideEffectClassification;

  @com.fasterxml.jackson.annotation.JsonPropertyDescription(
      "Hint for plan adaptation: always, conditional, or never.")
  private ReplanHint replanHint;

  @com.fasterxml.jackson.annotation.JsonPropertyDescription(
      "Alternative capability names activated on primary node failure.")
  private List<String> contingency;

  private Binding(String name, BindingTarget target, Trigger on) {
    this.name = name;
    this.target = target;
    this.on = on;
  }

  public void setWhen(ExpressionEvaluator when) {
    this.when = when;
  }

  public void setConflictResolverStrategy(String conflictResolverStrategy) {
    this.conflictResolverStrategy = conflictResolverStrategy;
  }

  public void setOutcomePolicy(OutcomePolicy outcomePolicy) {
    this.outcomePolicy = outcomePolicy;
  }

  public void setInputProjectionOverride(ExpressionEvaluator inputProjectionOverride) {
    this.inputProjectionOverride = inputProjectionOverride;
  }

  public void setContextWrite(Map<String, Object> contextWrite) {
    this.contextWrite = contextWrite;
  }

  public void setProducedKeys(Set<String> producedKeys) {
    this.producedKeys = producedKeys != null ? Set.copyOf(producedKeys) : Collections.emptySet();
  }

  public void setLifecycleScope(LifecycleScope lifecycleScope) {
    this.lifecycleScope = lifecycleScope;
  }

  public void setParticipation(Participation participation) {
    this.participation = participation;
  }

  public void setExecutionMode(ExecutionMode executionMode) {
    this.executionMode = executionMode;
  }

  public void setPermissionIntent(List<WorkerAction> permissionIntent) {
    this.permissionIntent = permissionIntent != null ? List.copyOf(permissionIntent) : null;
  }

  public BindingTarget target() {
    return target;
  }

  public String getName() {
    return name;
  }

  public Trigger getOn() {
    return on;
  }

  public ExpressionEvaluator getWhen() {
    return when;
  }

  /**
   * Strategy name for resolving concurrent writes to the same CaseContext key. Values:
   * "LAST_WRITER_WINS" (default), "FIRST_WRITER_WINS", "FAIL". Null means use the default
   * (LAST_WRITER_WINS). See casehubio/engine#45, #51.
   */
  public String getConflictResolverStrategy() {
    return conflictResolverStrategy;
  }

  public OutcomePolicy getOutcomePolicy() {
    return outcomePolicy;
  }

  public ExpressionEvaluator getInputProjectionOverride() {
    return inputProjectionOverride;
  }

  public Map<String, Object> getContextWrite() {
    return contextWrite;
  }

  /**
   * Keys this binding declares it will produce. Used for static analysis and audit trail. Empty by
   * default. Overlaps within the same stage trigger a validation warning.
   */
  public Set<String> getProducedKeys() {
    return producedKeys != null ? producedKeys : Collections.emptySet();
  }

  public LifecycleScope lifecycleScope() {
    return lifecycleScope != null ? lifecycleScope : LifecycleScope.BINDING;
  }

  public Participation participation() {
    return participation != null ? participation : Participation.PARTICIPANT;
  }

  public ExecutionMode executionMode() {
    return executionMode != null ? executionMode : ExecutionMode.TRANSIENT;
  }

  public ExpressionEvaluator effectiveInputProjection(CapabilityTarget capTarget) {
    return inputProjectionOverride != null ? inputProjectionOverride : capTarget.inputProjection();
  }

  public List<WorkerAction> getPermissionIntent() {
    return permissionIntent;
  }

  public void setExchangeProjectionStrategy(String exchangeProjectionStrategy) {
    this.exchangeProjectionStrategy = exchangeProjectionStrategy;
  }

  public String getExchangeProjectionStrategy() {
    return exchangeProjectionStrategy;
  }

  public void setExchangeProjectionExpression(String exchangeProjectionExpression) {
    this.exchangeProjectionExpression = exchangeProjectionExpression;
  }

  public String getExchangeProjectionExpression() {
    return exchangeProjectionExpression;
  }

  public void setProduces(String produces) {
    this.produces = produces;
  }

  public String getProduces() {
    return produces;
  }

  public void setConsumes(String consumes) {
    this.consumes = consumes;
  }

  public String getConsumes() {
    return consumes;
  }

  public RecoveryOverride getRecoveryOverride() {
    return recoveryOverride;
  }

  public void setRecoveryOverride(RecoveryOverride recoveryOverride) {
    this.recoveryOverride = recoveryOverride;
  }

  public SideEffectClassification getSideEffectClassification() {
    return sideEffectClassification != null
        ? sideEffectClassification
        : SideEffectClassification.UNKNOWN;
  }

  public void setSideEffectClassification(SideEffectClassification sideEffectClassification) {
    this.sideEffectClassification = sideEffectClassification;
  }

  public void setReplanHint(ReplanHint replanHint) {
    this.replanHint = replanHint;
  }

  public ReplanHint getReplanHint() {
    return replanHint != null ? replanHint : ReplanHint.CONDITIONAL;
  }

  public void setContingency(List<String> contingency) {
    this.contingency = contingency != null ? List.copyOf(contingency) : null;
  }

  public List<String> getContingency() {
    return contingency;
  }

  public static Builder builder() {
    return new Builder();
  }

  public static class Builder {

    private String name;
    private BindingTarget target;
    private Trigger on;
    private ExpressionEvaluator when;
    private String conflictResolverStrategy;
    private OutcomePolicy outcomePolicy;
    private ExpressionEvaluator inputProjectionOverride;
    private Map<String, Object> contextWrite;
    private Set<String> producedKeys;
    private LifecycleScope lifecycleScope;
    private Participation participation;
    private ExecutionMode executionMode;
    private List<WorkerAction> permissionIntent;
    private String exchangeProjectionStrategy;
    private String exchangeProjectionExpression;
    private String produces;
    private String consumes;
    private RecoveryOverride recoveryOverride;
    private SideEffectClassification sideEffectClassification;
    private ReplanHint replanHint;
    private List<String> contingency;

    private Builder() {}

    public Builder name(String name) {
      this.name = name;
      return this;
    }

    /** Convenience method — wraps {@code capability} in a {@link CapabilityTarget}. */
    public Builder capability(Capability capability) {
      this.target = new CapabilityTarget(capability);
      return this;
    }

    /** Convenience method — wraps {@code subCase} in a {@link SubCaseTarget}. */
    public Builder subCase(SubCase subCase) {
      this.target = new SubCaseTarget(subCase);
      return this;
    }

    /** Sets a {@link HumanTaskTarget} directly. */
    public Builder humanTask(HumanTaskTarget humanTask) {
      this.target = humanTask;
      return this;
    }

    /** Sets a {@link SignalTarget} from a payload map. */
    public Builder signal(Map<String, Object> payload) {
      this.target = new SignalTarget(payload);
      return this;
    }

    /** Sets any {@link BindingTarget} directly. */
    public Builder target(BindingTarget target) {
      this.target = target;
      return this;
    }

    public Builder on(Trigger on) {
      this.on = on;
      return this;
    }

    public Builder when(ExpressionEvaluator when) {
      this.when = when;
      return this;
    }

    public Builder when(String when) {
      this.when = new JQExpressionEvaluator(when);
      return this;
    }

    public Builder conflictResolverStrategy(String conflictResolverStrategy) {
      this.conflictResolverStrategy = conflictResolverStrategy;
      return this;
    }

    public Builder outcomePolicy(OutcomePolicy outcomePolicy) {
      this.outcomePolicy = outcomePolicy;
      return this;
    }

    public Builder inputProjectionOverride(String inputProjectionOverride) {
      this.inputProjectionOverride =
          inputProjectionOverride != null
              ? new JQExpressionEvaluator(inputProjectionOverride)
              : null;
      return this;
    }

    public Builder inputProjectionOverride(ExpressionEvaluator inputProjectionOverride) {
      this.inputProjectionOverride = inputProjectionOverride;
      return this;
    }

    public Builder contextWrite(Map<String, Object> contextWrite) {
      this.contextWrite = contextWrite;
      return this;
    }

    public Builder producedKeys(Set<String> producedKeys) {
      this.producedKeys = producedKeys;
      return this;
    }

    public Builder lifecycleScope(LifecycleScope lifecycleScope) {
      this.lifecycleScope = lifecycleScope;
      return this;
    }

    public Builder participation(Participation participation) {
      this.participation = participation;
      return this;
    }

    public Builder executionMode(ExecutionMode executionMode) {
      this.executionMode = executionMode;
      return this;
    }

    public Builder permissionIntent(List<WorkerAction> permissionIntent) {
      this.permissionIntent = permissionIntent;
      return this;
    }

    public Builder exchangeProjectionStrategy(String strategy) {
      this.exchangeProjectionStrategy = strategy;
      return this;
    }

    public Builder exchangeOnly() {
      this.exchangeProjectionStrategy = "exchange-only";
      return this;
    }

    public Builder dualWrite() {
      this.exchangeProjectionStrategy = "dual-write";
      return this;
    }

    public Builder projectWith(String strategy, String expression) {
      this.exchangeProjectionStrategy = strategy;
      this.exchangeProjectionExpression = expression;
      return this;
    }

    public Builder produces(String channelName) {
      this.produces = channelName;
      return this;
    }

    public Builder consumes(String channelName) {
      this.consumes = channelName;
      return this;
    }

    public Builder recoveryOverride(RecoveryOverride recoveryOverride) {
      this.recoveryOverride = recoveryOverride;
      return this;
    }

    public Builder sideEffectClassification(SideEffectClassification sideEffectClassification) {
      this.sideEffectClassification = sideEffectClassification;
      return this;
    }

    public Builder replanHint(ReplanHint replanHint) {
      this.replanHint = replanHint;
      return this;
    }

    public Builder contingency(List<String> capabilities) {
      this.contingency = capabilities;
      return this;
    }

    public Builder contingency(String... capabilities) {
      this.contingency = java.util.Arrays.asList(capabilities);
      return this;
    }

    public Binding build() {
      Objects.requireNonNull(name);
      Objects.requireNonNull(on);
      if (target == null) {
        throw new IllegalStateException(
            "Binding '" + name + "' must have a target (capability, subCase, or humanTask)");
      }

      LifecycleScope ls =
          this.lifecycleScope != null ? this.lifecycleScope : LifecycleScope.BINDING;
      ExecutionMode em = this.executionMode != null ? this.executionMode : ExecutionMode.TRANSIENT;
      Participation p = this.participation != null ? this.participation : Participation.PARTICIPANT;

      if (ls == LifecycleScope.BINDING && em != ExecutionMode.TRANSIENT) {
        throw new IllegalArgumentException(
            "BINDING scope requires TRANSIENT execution mode, got " + em);
      }
      if (p == Participation.COMPANION && ls == LifecycleScope.BINDING) {
        throw new IllegalArgumentException(
            "COMPANION requires COMPOUND or CASE scope, got BINDING");
      }
      if (on instanceof ScopeActivatedTrigger && ls == LifecycleScope.BINDING) {
        throw new IllegalArgumentException(
            "ScopeActivatedTrigger requires COMPOUND or CASE scope, got BINDING");
      }
      if (target instanceof SignalTarget && ls != LifecycleScope.BINDING) {
        throw new IllegalArgumentException("SignalTarget requires BINDING scope, got " + ls);
      }
      if (target instanceof SignalTarget && p == Participation.COMPANION) {
        throw new IllegalArgumentException("SignalTarget cannot use COMPANION participation");
      }
      if (ls == LifecycleScope.CASE && p != Participation.COMPANION) {
        throw new IllegalArgumentException("CASE scope requires COMPANION participation, got " + p);
      }
      if (ls != LifecycleScope.BINDING && !(target instanceof CapabilityTarget)) {
        throw new IllegalArgumentException(
            "Lifecycle scope "
                + ls
                + " requires CapabilityTarget, got "
                + target.getClass().getSimpleName());
      }

      Binding b = new Binding(name, target, on);
      b.setWhen(when);
      b.setConflictResolverStrategy(conflictResolverStrategy);
      b.setOutcomePolicy(outcomePolicy);
      b.setInputProjectionOverride(inputProjectionOverride);
      b.setContextWrite(contextWrite);
      b.setProducedKeys(producedKeys);
      b.setLifecycleScope(this.lifecycleScope);
      b.setParticipation(this.participation);
      b.setExecutionMode(this.executionMode);
      b.setPermissionIntent(this.permissionIntent);
      b.setExchangeProjectionStrategy(this.exchangeProjectionStrategy);
      b.setExchangeProjectionExpression(this.exchangeProjectionExpression);
      b.setProduces(this.produces);
      b.setConsumes(this.consumes);
      b.setRecoveryOverride(this.recoveryOverride);
      b.setSideEffectClassification(this.sideEffectClassification);
      b.setReplanHint(this.replanHint);
      b.setContingency(this.contingency);
      return b;
    }
  }
}
