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
package io.casehub.engine.rest.service;

import io.casehub.api.acl.EngineResourceTypes;
import io.casehub.api.context.CaseContext;
import io.casehub.api.engine.CaseHubRuntime;
import io.casehub.api.engine.ExpressionEngineRegistry;
import io.casehub.api.model.AllOfGoalExpression;
import io.casehub.api.model.AnyOfGoalExpression;
import io.casehub.api.model.CaseCompletion;
import io.casehub.api.model.CaseDefinition;
import io.casehub.api.model.Goal;
import io.casehub.api.model.GoalBasedCompletion;
import io.casehub.api.model.GoalExpression;
import io.casehub.api.model.GoalKind;
import io.casehub.api.model.PredicateBasedCompletion;
import io.casehub.api.model.SingleGoalExpression;
import io.casehub.api.model.evaluator.JQExpressionEvaluator;
import io.casehub.engine.common.internal.model.CaseInstance;
import io.casehub.engine.common.internal.model.CaseMetaModel;
import io.casehub.engine.common.spi.CaseDefinitionRegistry;
import io.casehub.engine.common.spi.CaseInstanceRepository;
import io.casehub.engine.rest.dto.CompletionStatus;
import io.casehub.engine.rest.dto.CompletionSummary;
import io.casehub.engine.rest.dto.GoalEvaluationResponse;
import io.casehub.engine.rest.dto.GoalStatusResponse;
import io.casehub.engine.rest.exception.EntityNotFoundException;
import io.casehub.platform.api.acl.AccessControlProvider;
import io.casehub.platform.api.acl.AccessDeniedException;
import io.casehub.platform.api.acl.AclAction;
import io.casehub.platform.api.acl.ResourceId;
import io.casehub.platform.api.identity.CurrentPrincipal;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.jboss.logging.Logger;

@ApplicationScoped
public class CaseService {

  private static final Logger LOG = Logger.getLogger(CaseService.class);
  @Inject AccessControlProvider accessControlProvider;
  @Inject CurrentPrincipal currentPrincipal;
  @Inject CaseDefinitionRegistry definitionRegistry;
  @Inject CaseHubRuntime runtime;
  @Inject CaseInstanceRepository instanceRepository;
  @Inject ExpressionEngineRegistry expressionEngineRegistry;

  public CaseInstance startCase(
      String namespace,
      String name,
      String version,
      Map<String, Object> context,
      String tenancyId) {
    var metaModel =
        definitionRegistry
            .findByIdentity(namespace, name, version)
            .orElseThrow(
                () ->
                    new EntityNotFoundException(
                        String.format("No definition for %s/%s/%s", namespace, name, version)));

    var definition = definitionRegistry.getCaseDefinition(metaModel);
    if (definition == null) {
      throw new EntityNotFoundException(
          String.format(
              "Definition metadata exists but body not found for %s/%s/%s",
              namespace, name, version));
    }

    UUID caseId = runtime.startCase(definition, context);

    CaseInstance instance = instanceRepository.findByUuid(caseId, tenancyId);
    if (instance == null) {
      throw new RuntimeException("Case created (id=" + caseId + ") but not found in repository");
    }
    return instance;
  }

  public CaseInstance requireCase(UUID caseId, String tenancyId) {
    CaseInstance instance = instanceRepository.findByUuid(caseId, tenancyId);
    if (instance == null) {
      throw new EntityNotFoundException("Case not found: " + caseId);
    }
    return instance;
  }

  public CaseInstance requireCaseAccess(UUID caseId, AclAction action) {
    String tenancyId = currentPrincipal.tenancyId();
    CaseInstance instance = instanceRepository.findByUuid(caseId, tenancyId);
    if (instance == null) {
      throw new EntityNotFoundException("Case not found: " + caseId);
    }
    String actorId = currentPrincipal.actorId();
    ResourceId resourceId = new ResourceId(EngineResourceTypes.CASE, caseId.toString());
    if (!accessControlProvider.canAccess(actorId, resourceId, action)) {
      LOG.warnf("ACL denied: actor=%s resource=%s action=%s", actorId, resourceId, action);
      throw new AccessDeniedException(actorId, resourceId, action);
    }
    return instance;
  }

  public GoalEvaluationResponse evaluateGoals(UUID caseId, String tenancyId) {
    CaseInstance instance = requireCaseAccess(caseId, AclAction.READ);

    CaseMetaModel meta = instance.getCaseMetaModel();
    CaseDefinition definition = definitionRegistry.getCaseDefinition(meta);
    if (definition == null) {
      throw new EntityNotFoundException("Case definition not found for case: " + caseId);
    }

    CaseContext caseContext = (CaseContext) runtime.query(caseId, ".");

    List<GoalStatusResponse> goalResponses = new ArrayList<>();
    Set<String> reachedGoalNames = new HashSet<>();

    for (Goal goal : definition.getGoals()) {
      String conditionStr = null;
      if (goal.getCondition() instanceof JQExpressionEvaluator jq) {
        conditionStr = jq.expression();
      }

      boolean satisfied = false;
      String error = null;
      try {
        satisfied = expressionEngineRegistry.evaluate(goal.getCondition(), caseContext);
      } catch (Exception e) {
        error = e.getMessage();
      }

      if (satisfied && error == null) {
        reachedGoalNames.add(goal.getName());
      }

      goalResponses.add(
          new GoalStatusResponse(goal.getName(), goal.getKind(), satisfied, conditionStr, error));
    }

    CompletionSummary completion =
        buildCompletionSummary(definition.getCompletion(), reachedGoalNames, caseContext);

    return new GoalEvaluationResponse(goalResponses, completion);
  }

  private CompletionSummary buildCompletionSummary(
      CaseCompletion caseCompletion, Set<String> reachedGoalNames, CaseContext caseContext) {
    if (caseCompletion == null) {
      return null;
    }

    if (caseCompletion instanceof GoalBasedCompletion<?> goalBased) {
      Map<String, CompletionStatus> byKind = new LinkedHashMap<>();
      for (var entry : goalBased.getGoals().entrySet()) {
        GoalKind kind = entry.getKey();
        GoalExpression expr = entry.getValue();
        boolean sat = expr.isSatisfiedBy(reachedGoalNames);
        String exprType =
            switch (expr) {
              case AllOfGoalExpression ignored -> "allOf";
              case AnyOfGoalExpression ignored -> "anyOf";
              case SingleGoalExpression ignored -> "single";
            };
        byKind.put(kind.value(), new CompletionStatus(sat, exprType));
      }
      return new CompletionSummary("goal-based", null, byKind);
    }

    if (caseCompletion instanceof PredicateBasedCompletion predBased) {
      boolean sat = false;
      try {
        sat = expressionEngineRegistry.evaluate(predBased.getDoneWhen(), caseContext);
      } catch (Exception ignored) {
      }
      return new CompletionSummary("predicate-based", sat, Map.of());
    }

    return null;
  }
}
