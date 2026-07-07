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
import io.casehub.api.model.evaluator.ExpressionEvaluator;
import io.casehub.api.model.evaluator.JQExpressionEvaluator;
import io.casehub.worker.api.Capability;
import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public class Binding {

  @JsonProperty("target")
  private final BindingTarget target;

  private final String name;
  private final Trigger on;
  private ExpressionEvaluator when;
  private String conflictResolverStrategy;
  private OutcomePolicy outcomePolicy;
  private String inputSchemaOverride;
  private Map<String, Object> contextWrite;
  private Set<String> producedKeys;

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

  public void setInputSchemaOverride(String inputSchemaOverride) {
    this.inputSchemaOverride = inputSchemaOverride;
  }

  public void setContextWrite(Map<String, Object> contextWrite) {
    this.contextWrite = contextWrite;
  }

  public void setProducedKeys(Set<String> producedKeys) {
    this.producedKeys = producedKeys != null ? Set.copyOf(producedKeys) : Collections.emptySet();
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

  public String getInputSchemaOverride() {
    return inputSchemaOverride;
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

  public String effectiveInputSchema(Capability capability) {
    return inputSchemaOverride != null ? inputSchemaOverride : capability.inputSchema();
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
    private String inputSchemaOverride;
    private Map<String, Object> contextWrite;
    private Set<String> producedKeys;

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

    public Builder inputSchemaOverride(String inputSchemaOverride) {
      this.inputSchemaOverride = inputSchemaOverride;
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

    public Binding build() {
      Objects.requireNonNull(name);
      Objects.requireNonNull(on);
      if (target == null) {
        throw new IllegalStateException(
            "Binding '" + name + "' must have a target (capability, subCase, or humanTask)");
      }
      Binding b = new Binding(name, target, on);
      b.setWhen(when);
      b.setConflictResolverStrategy(conflictResolverStrategy);
      b.setOutcomePolicy(outcomePolicy);
      b.setInputSchemaOverride(inputSchemaOverride);
      b.setContextWrite(contextWrite);
      b.setProducedKeys(producedKeys);
      return b;
    }
  }
}
