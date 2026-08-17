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
package io.casehub.engine.rest.dto;

import io.casehub.api.model.CaseDefinition;
import io.casehub.engine.plan.execution.CasePlanModelSnapshot;
import io.casehub.engine.plan.execution.DagResultSnapshot;
import io.casehub.engine.plan.execution.NodeStateSnapshot;
import io.casehub.engine.plan.snapshot.DagNodeSnapshot;
import io.casehub.engine.plan.snapshot.DagPlanSnapshot;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

public record ExecutionStateSnapshot(
    String executionId,
    String state,
    ExecutionModelSnapshot model,
    String result,
    List<AgentRefSnapshot> activeAgents,
    List<AgentResultSnapshot> completedAgents,
    Integer iteration,
    Instant startedAt,
    Instant completedAt) {

  private static final Set<String> TERMINAL_STATUSES =
      Set.of("COMPLETED", "FAULTED", "REJECTED", "OBSOLETE", "CANCELLED");

  public record ExecutionModelSnapshot(
      String pattern,
      String routingStrategy,
      String decompositionStrategy,
      String activationStrategy,
      String aggregationStrategy,
      String terminationStrategy,
      FailurePolicySnapshot failurePolicy) {}

  public record FailurePolicySnapshot(
      String routingFailureAction,
      String aggregationFailureAction,
      AgentRetryPolicySnapshot agentRetryPolicy) {}

  public record AgentRetryPolicySnapshot(
      int maxRetries, String backoffStrategy, Integer initialDelayMs) {}

  public record AgentRefSnapshot(String id, String type, String name) {}

  public record AgentResultSnapshot(
      AgentRefSnapshot agentRef, String status, String detail, String error, Long duration) {}

  public static ExecutionStateSnapshot compose(
      UUID caseId,
      CasePlanModelSnapshot planModel,
      DagPlanSnapshot dagPlan,
      DagResultSnapshot dagResult) {
    return compose(caseId, planModel, dagPlan, dagResult, null);
  }

  public static ExecutionStateSnapshot compose(
      UUID caseId,
      CasePlanModelSnapshot planModel,
      DagPlanSnapshot dagPlan,
      DagResultSnapshot dagResult,
      CaseDefinition definition) {
    return new ExecutionStateSnapshot(
        caseId.toString(),
        deriveState(planModel, dagResult),
        deriveModel(dagPlan, definition),
        deriveResult(dagResult),
        deriveActiveAgents(planModel),
        deriveCompletedAgents(planModel, dagPlan, dagResult),
        null,
        deriveStartedAt(planModel, dagPlan),
        dagResult != null ? dagResult.timestamp() : null);
  }

  static String deriveState(CasePlanModelSnapshot planModel, DagResultSnapshot dagResult) {
    if (dagResult != null) {
      if (!dagResult.nodeStates().isEmpty()
          && dagResult.nodeStates().values().stream().allMatch(s -> "Cancelled".equals(s.kind()))) {
        return "CANCELLED";
      }
      return dagResult.allSucceeded() ? "COMPLETE" : "FAULTED";
    }
    if (planModel != null && !planModel.agenda().isEmpty()) {
      var activeItems =
          planModel.agenda().stream().filter(a -> !TERMINAL_STATUSES.contains(a.status())).toList();
      if (!activeItems.isEmpty()) {
        if (activeItems.stream().allMatch(a -> "DELEGATED".equals(a.status()))) {
          return "WAITING_FOR_AGENT";
        }
        if (activeItems.stream().allMatch(a -> "SUSPENDED".equals(a.status()))) {
          return "WAITING_FOR_EVENT";
        }
      }
      return "RUNNING";
    }
    return "IDLE";
  }

  static String deriveResult(DagResultSnapshot dagResult) {
    if (dagResult == null) return null;
    if (!dagResult.nodeStates().isEmpty()
        && dagResult.nodeStates().values().stream().allMatch(s -> "Cancelled".equals(s.kind()))) {
      return "CANCELLED";
    }
    return dagResult.allSucceeded() ? "COMPLETED" : "FAILED";
  }

  static ExecutionModelSnapshot deriveModel(DagPlanSnapshot dagPlan, CaseDefinition definition) {
    String routingStrategy = null;
    String decompositionStrategy = null;
    if (definition != null) {
      routingStrategy = definition.getAgentRouting();
      decompositionStrategy = definition.getDecompositionStrategy();
    }
    return new ExecutionModelSnapshot(
        detectPattern(dagPlan),
        routingStrategy,
        decompositionStrategy,
        null,
        null,
        null,
        new FailurePolicySnapshot("RETRY_BROADER", "ESCALATE", null));
  }

  static String detectPattern(DagPlanSnapshot dagPlan) {
    if (dagPlan == null || dagPlan.nodes().size() <= 1) return "SEQUENCE";
    var nodes = dagPlan.nodes().values();
    if (nodes.stream().allMatch(n -> n.dependsOn() == null || n.dependsOn().isEmpty())) {
      return "PARALLEL";
    }
    if (nodes.stream().allMatch(n -> n.dependsOn() == null || n.dependsOn().size() <= 1)) {
      return "SEQUENCE";
    }
    return "HTN";
  }

  static List<AgentRefSnapshot> deriveActiveAgents(CasePlanModelSnapshot planModel) {
    if (planModel == null) return List.of();
    return planModel.agenda().stream()
        .filter(item -> !TERMINAL_STATUSES.contains(item.status()))
        .map(
            item ->
                new AgentRefSnapshot(
                    item.planItemId(),
                    item.targetType() != null ? item.targetType() : "WORKER",
                    item.bindingName()))
        .toList();
  }

  static List<AgentResultSnapshot> deriveCompletedAgents(
      CasePlanModelSnapshot planModel, DagPlanSnapshot dagPlan, DagResultSnapshot dagResult) {
    if (planModel != null) {
      return planModel.agenda().stream()
          .filter(item -> TERMINAL_STATUSES.contains(item.status()))
          .map(
              item -> {
                NodeStateSnapshot nodeState = findNodeState(item.planItemId(), dagPlan, dagResult);
                String error = nodeState != null ? nodeState.reason() : null;
                Long duration = findNodeDuration(item.planItemId(), dagPlan, dagResult);
                return new AgentResultSnapshot(
                    new AgentRefSnapshot(
                        item.planItemId(),
                        item.targetType() != null ? item.targetType() : "WORKER",
                        item.bindingName()),
                    mapTaskStatusToResult(item.status(), error),
                    null,
                    error,
                    duration);
              })
          .toList();
    }
    if (dagResult != null && dagPlan != null) {
      return dagResult.nodeStates().entrySet().stream()
          .filter(e -> isNodeTerminal(e.getValue()))
          .map(
              e -> {
                DagNodeSnapshot dagNode = dagPlan.nodes().get(e.getKey());
                Long duration =
                    dagResult.nodeDurationsMs() != null
                        ? dagResult.nodeDurationsMs().get(e.getKey())
                        : null;
                return new AgentResultSnapshot(
                    new AgentRefSnapshot(
                        e.getKey(),
                        "WORKER",
                        dagNode != null ? dagNode.executorName() : e.getKey()),
                    mapNodeKindToResult(e.getValue().kind()),
                    null,
                    e.getValue().reason(),
                    duration);
              })
          .toList();
    }
    return List.of();
  }

  private static NodeStateSnapshot findNodeState(
      String planItemId, DagPlanSnapshot dagPlan, DagResultSnapshot dagResult) {
    if (dagResult == null || dagPlan == null) return null;
    for (var entry : dagPlan.nodes().entrySet()) {
      if (planItemId.equals(entry.getValue().taskId())) {
        return dagResult.nodeStates().get(entry.getKey());
      }
    }
    return null;
  }

  private static Long findNodeDuration(
      String planItemId, DagPlanSnapshot dagPlan, DagResultSnapshot dagResult) {
    if (dagResult == null || dagPlan == null || dagResult.nodeDurationsMs() == null) return null;
    for (var entry : dagPlan.nodes().entrySet()) {
      if (planItemId.equals(entry.getValue().taskId())) {
        return dagResult.nodeDurationsMs().get(entry.getKey());
      }
    }
    return null;
  }

  private static boolean isNodeTerminal(NodeStateSnapshot state) {
    return switch (state.kind()) {
      case "Completed", "Failed", "Skipped", "Cancelled" -> true;
      default -> false;
    };
  }

  static String mapTaskStatusToResult(String status, String reason) {
    if ("FAULTED".equals(status) && reason != null && isTimeoutReason(reason)) {
      return "TIMEOUT";
    }
    return switch (status) {
      case "COMPLETED" -> "SUCCESS";
      case "REJECTED" -> "DECLINED";
      default -> "FAILURE";
    };
  }

  static String mapNodeKindToResult(String kind) {
    return switch (kind) {
      case "Completed" -> "SUCCESS";
      case "Skipped" -> "DECLINED";
      default -> "FAILURE";
    };
  }

  private static boolean isTimeoutReason(String reason) {
    String lower = reason.toLowerCase();
    return lower.contains("timeout") || lower.contains("timed out") || lower.contains("expired");
  }

  private static Instant deriveStartedAt(CasePlanModelSnapshot planModel, DagPlanSnapshot dagPlan) {
    if (dagPlan != null) return dagPlan.timestamp();
    if (planModel != null) return planModel.timestamp();
    return null;
  }
}
