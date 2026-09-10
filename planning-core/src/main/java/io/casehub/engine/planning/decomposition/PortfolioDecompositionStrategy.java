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
package io.casehub.engine.planning.decomposition;

import com.fasterxml.jackson.databind.JsonNode;
import io.casehub.api.model.CaseDefinition;
import io.casehub.api.model.ai.AgentException;
import io.casehub.engine.plan.DagPlan;
import io.casehub.engine.plan.DecompositionContext;
import io.casehub.engine.plan.DecompositionStrategy;
import io.casehub.engine.plan.PortfolioConfig;
import io.casehub.engine.plan.TaskNode;
import io.casehub.platform.api.routing.StrategyResolver;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.jboss.logging.Logger;

@ApplicationScoped
public class PortfolioDecompositionStrategy implements DecompositionStrategy<JsonNode> {

  private static final Logger LOG = Logger.getLogger(PortfolioDecompositionStrategy.class);

  private final StrategyResolver strategyResolver;

  @Inject
  public PortfolioDecompositionStrategy(StrategyResolver strategyResolver) {
    this.strategyResolver = strategyResolver;
  }

  @Override
  public String id() {
    return "portfolio";
  }

  @Override
  @SuppressWarnings("unchecked")
  public DagPlan<TaskNode.LeafTask<JsonNode>> decompose(
      TaskNode<JsonNode> task, DecompositionContext<JsonNode> context) {

    PortfolioConfig config = resolveConfig(context);
    List<PortfolioAttempt> attempts = new ArrayList<>();

    for (String delegateId : config.delegates()) {
      if ("portfolio".equals(delegateId)) {
        continue;
      }

      Optional<DecompositionStrategy> delegateOpt =
          strategyResolver.find(DecompositionStrategy.class, delegateId);
      if (delegateOpt.isEmpty()) {
        LOG.warnf("Portfolio: delegate '%s' not found — skipping", delegateId);
        attempts.add(PortfolioAttempt.skipped(delegateId, "not found"));
        continue;
      }

      DecompositionStrategy<JsonNode> delegate =
          (DecompositionStrategy<JsonNode>) delegateOpt.get();
      long timeoutMs = config.timeoutFor(delegateId);
      long startNanos = System.nanoTime();

      try {
        DagPlan<TaskNode.LeafTask<JsonNode>> result =
            executeWithTimeout(delegate, task, context, timeoutMs);
        long durationMs =
            java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);
        LOG.infof("Portfolio: delegate '%s' succeeded in %dms", delegateId, durationMs);
        attempts.add(PortfolioAttempt.success(delegateId, durationMs));
        return result;
      } catch (TimeoutException e) {
        long durationMs =
            java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);
        LOG.infof("Portfolio: delegate '%s' timed out after %dms", delegateId, durationMs);
        attempts.add(PortfolioAttempt.timeout(delegateId, durationMs));
      } catch (Exception e) {
        long durationMs =
            java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);
        LOG.infof(
            "Portfolio: delegate '%s' failed after %dms: %s",
            delegateId, durationMs, e.getMessage());
        attempts.add(PortfolioAttempt.failed(delegateId, durationMs, e.getMessage()));
      }
    }

    throw new AgentException("Portfolio: no strategy produced a plan. Attempted: " + attempts);
  }

  private DagPlan<TaskNode.LeafTask<JsonNode>> executeWithTimeout(
      DecompositionStrategy<JsonNode> delegate,
      TaskNode<JsonNode> task,
      DecompositionContext<JsonNode> context,
      long timeoutMs)
      throws Exception {
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      var future = executor.submit(() -> delegate.decompose(task, context));
      try {
        return future.get(timeoutMs, TimeUnit.MILLISECONDS);
      } catch (TimeoutException e) {
        future.cancel(true);
        throw e;
      } catch (ExecutionException e) {
        if (e.getCause() instanceof Exception cause) {
          throw cause;
        }
        throw new AgentException("Delegate execution failed", e.getCause());
      }
    }
  }

  private PortfolioConfig resolveConfig(DecompositionContext<JsonNode> context) {
    if (context instanceof GoalDecompositionContext gdc) {
      CaseDefinition def = gdc.definition();
      if (def != null && def.getPortfolioConfig() != null) {
        return def.getPortfolioConfig();
      }
    }
    return PortfolioConfig.defaults();
  }

  record PortfolioAttempt(String delegateId, Status status, long durationMs, String detail) {

    enum Status {
      SUCCESS,
      FAILED,
      SKIPPED,
      TIMEOUT
    }

    static PortfolioAttempt success(String id, long ms) {
      return new PortfolioAttempt(id, Status.SUCCESS, ms, null);
    }

    static PortfolioAttempt failed(String id, long ms, String reason) {
      return new PortfolioAttempt(id, Status.FAILED, ms, reason);
    }

    static PortfolioAttempt skipped(String id, String reason) {
      return new PortfolioAttempt(id, Status.SKIPPED, 0, reason);
    }

    static PortfolioAttempt timeout(String id, long ms) {
      return new PortfolioAttempt(id, Status.TIMEOUT, ms, "timeout");
    }
  }
}
