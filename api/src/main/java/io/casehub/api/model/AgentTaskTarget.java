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
import io.casehub.platform.api.expression.ExpressionEvaluator;
import java.util.Objects;

public final class AgentTaskTarget implements BindingTarget {

  private final String capability;
  private final String domain;
  private final ExpressionEvaluator inputMapping;
  private final ExpressionEvaluator outputMapping;
  private final String model;

  private AgentTaskTarget(Builder builder) {
    this.capability = Objects.requireNonNull(builder.capability, "capability is required");
    this.domain = builder.domain;
    this.inputMapping = builder.inputMapping;
    this.outputMapping = builder.outputMapping;
    this.model = builder.model;
  }

  public String capability() {
    return capability;
  }

  public String domain() {
    return domain;
  }

  public ExpressionEvaluator inputMapping() {
    return inputMapping;
  }

  public ExpressionEvaluator outputMapping() {
    return outputMapping;
  }

  public String model() {
    return model;
  }

  public static Builder builder() {
    return new Builder();
  }

  public static final class Builder {

    private String capability;
    private String domain;
    private ExpressionEvaluator inputMapping;
    private ExpressionEvaluator outputMapping;
    private String model;

    public Builder capability(String capability) {
      this.capability = capability;
      return this;
    }

    public Builder domain(String domain) {
      this.domain = domain;
      return this;
    }

    public Builder inputMapping(String jq) {
      this.inputMapping = new JQExpressionEvaluator(jq);
      return this;
    }

    public Builder inputMapping(ExpressionEvaluator e) {
      this.inputMapping = e;
      return this;
    }

    public Builder outputMapping(String jq) {
      this.outputMapping = new JQExpressionEvaluator(jq);
      return this;
    }

    public Builder outputMapping(ExpressionEvaluator e) {
      this.outputMapping = e;
      return this;
    }

    public Builder model(String model) {
      this.model = model;
      return this;
    }

    public AgentTaskTarget build() {
      return new AgentTaskTarget(this);
    }
  }
}
