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
package io.casehub.engine.internal.engine.policy;

import io.casehub.api.engine.CaseEvaluationPolicy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Singleton;
import java.util.Optional;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/**
 * CDI producer that wires the configured {@link CaseEvaluationPolicy}. Reads {@code
 * casehub.engine.evaluation-policy} from Quarkus configuration and instantiates the corresponding
 * implementation.
 *
 * <p>Custom implementations can override via {@code @Alternative @Priority}.
 *
 * <p>Refs casehubio/engine#771.
 */
@ApplicationScoped
public class CaseEvaluationPolicyProducer {

  private static final Logger LOG = Logger.getLogger(CaseEvaluationPolicyProducer.class);

  @ConfigProperty(name = "casehub.engine.evaluation-policy", defaultValue = "coalescing")
  String policyName;

  @ConfigProperty(name = "casehub.engine.evaluation-policy.max-concurrent", defaultValue = "1")
  int maxConcurrent;

  @ConfigProperty(
      name = "casehub.engine.evaluation-policy.settlement.inner",
      defaultValue = "coalescing")
  Optional<String> settlementInner;

  @Produces
  @Singleton
  public CaseEvaluationPolicy produce() {
    CaseEvaluationPolicy policy =
        switch (policyName) {
          case "pass-through" -> {
            LOG.info("CaseEvaluationPolicy: pass-through (no gating)");
            yield new PassThroughPolicy();
          }
          case "coalescing" -> {
            LOG.info("CaseEvaluationPolicy: coalescing serializer");
            yield new CoalescingSerializerPolicy();
          }
          case "bounded" -> {
            LOG.infof("CaseEvaluationPolicy: bounded concurrency (permits=%d)", maxConcurrent);
            yield new BoundedConcurrencyPolicy(maxConcurrent);
          }
          case "settlement-gated" -> {
            CaseEvaluationPolicy inner = buildInnerPolicy(settlementInner.orElse("coalescing"));
            LOG.infof("CaseEvaluationPolicy: settlement-gated (inner=%s)", settlementInner);
            yield new SettlementGatedPolicy(inner);
          }
          default -> {
            LOG.warnf(
                "Unknown evaluation policy '%s' — falling back to coalescing serializer",
                policyName);
            yield new CoalescingSerializerPolicy();
          }
        };
    return policy;
  }

  private CaseEvaluationPolicy buildInnerPolicy(String name) {
    return switch (name) {
      case "pass-through" -> new PassThroughPolicy();
      case "coalescing" -> new CoalescingSerializerPolicy();
      case "bounded" -> new BoundedConcurrencyPolicy(maxConcurrent);
      default -> {
        LOG.warnf("Unknown inner policy '%s' for settlement-gated — using coalescing", name);
        yield new CoalescingSerializerPolicy();
      }
    };
  }
}
