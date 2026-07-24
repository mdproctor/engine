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
import org.jspecify.annotations.Nullable;

/**
 * Declares a mapping from an inbound connector message to a typed case signal.
 *
 * <p>Declared on {@link CaseDefinition}. At runtime, {@code InboundSignalBridge} matches incoming
 * {@code InboundMessage} events by {@code connectorType}, evaluates the {@code correlation}
 * expression to find the case, evaluates the {@code payload} expression to extract typed data, and
 * delivers a typed signal via {@code CaseHubRuntime.signal()}.
 */
public record InboundSignalMapping(
    String signalName,
    String connectorType,
    ExpressionEvaluator correlation,
    ExpressionEvaluator payload,
    @Nullable String correlationResolver) {

  public static Builder builder() {
    return new Builder();
  }

  public static final class Builder {
    private String signalName;
    private String connectorType;
    private ExpressionEvaluator correlation;
    private ExpressionEvaluator payload;
    private String correlationResolver;

    private Builder() {}

    public Builder signalName(String signalName) {
      this.signalName = signalName;
      return this;
    }

    public Builder connectorType(String connectorType) {
      this.connectorType = connectorType;
      return this;
    }

    public Builder correlation(String jqExpression) {
      this.correlation = new JQExpressionEvaluator(jqExpression);
      return this;
    }

    public Builder correlation(ExpressionEvaluator evaluator) {
      this.correlation = evaluator;
      return this;
    }

    public Builder payload(String jqExpression) {
      this.payload = new JQExpressionEvaluator(jqExpression);
      return this;
    }

    public Builder payload(ExpressionEvaluator evaluator) {
      this.payload = evaluator;
      return this;
    }

    public Builder correlationResolver(String correlationResolver) {
      this.correlationResolver = correlationResolver;
      return this;
    }

    public InboundSignalMapping build() {
      if (signalName == null || signalName.isBlank()) {
        throw new IllegalStateException("InboundSignalMapping requires a non-blank signalName");
      }
      if (connectorType == null || connectorType.isBlank()) {
        throw new IllegalStateException("InboundSignalMapping requires a non-blank connectorType");
      }
      Objects.requireNonNull(correlation, "correlation expression is required");
      Objects.requireNonNull(payload, "payload expression is required");
      return new InboundSignalMapping(
          signalName, connectorType, correlation, payload, correlationResolver);
    }
  }
}
