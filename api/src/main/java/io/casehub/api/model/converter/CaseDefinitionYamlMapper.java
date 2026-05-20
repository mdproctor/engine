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
package io.casehub.api.model.converter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import io.casehub.api.model.AllOfGoalExpression;
import io.casehub.api.model.AnyOfGoalExpression;
import io.casehub.api.model.Binding;
import io.casehub.api.model.Capability;
import io.casehub.api.model.CaseDefinition;
import io.casehub.api.model.Goal;
import io.casehub.api.model.GoalBasedCompletion;
import io.casehub.api.model.GoalExpression;
import io.casehub.api.model.GoalKind;
import io.casehub.api.model.HumanTaskTarget;
import io.casehub.api.model.Milestone;
import io.casehub.api.model.Worker;
import io.casehub.api.model.evaluator.JQExpressionEvaluator;
import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Centralized YAML marshaller for CaseDefinition.
 *
 * <p>Reads YAML CaseDefinition files, deserializes to generated schema models (io.casehub.model.*),
 * and converts to API models (io.casehub.api.model.*).
 *
 * <p>Uses a default ObjectMapper with YAMLFactory. Runtime module can override via {@link
 * #setObjectMapper(ObjectMapper)} to inject a CDI-managed instance.
 */
public final class CaseDefinitionYamlMapper {

  private static ObjectMapper yamlMapper = new ObjectMapper(new YAMLFactory());

  private CaseDefinitionYamlMapper() {}

  /**
   * Sets the ObjectMapper to use for YAML parsing.
   *
   * <p>Intended for runtime module to inject CDI-managed ObjectMapper with config/secret support.
   *
   * @param mapper ObjectMapper instance (must use YAMLFactory)
   */
  public static void setObjectMapper(ObjectMapper mapper) {
    yamlMapper = mapper;
  }

  /**
   * Loads a CaseDefinition from a YAML InputStream.
   *
   * @param yamlStream InputStream containing YAML CaseDefinition
   * @return API model CaseDefinition
   * @throws IOException if reading or parsing fails
   */
  public static CaseDefinition load(InputStream yamlStream) throws IOException {
    if (yamlStream == null) {
      throw new IllegalArgumentException("InputStream cannot be null");
    }
    io.casehub.model.CaseDefinition schema =
        yamlMapper.readValue(yamlStream, io.casehub.model.CaseDefinition.class);
    return convertToApiModel(schema);
  }

  /**
   * Converts generated schema model to API model.
   *
   * @param schema generated CaseDefinition from YAML
   * @return API model CaseDefinition
   */
  private static CaseDefinition convertToApiModel(io.casehub.model.CaseDefinition schema) {
    CaseDefinition def =
        new CaseDefinition(schema.getNamespace(), schema.getName(), schema.getVersion());
    def.setDsl(schema.getDsl());
    def.setTitle(schema.getTitle());

    // Convert capabilities
    Map<String, Capability> capabilityMap = new java.util.LinkedHashMap<>();
    if (schema.getSpec().getCapabilities() != null) {
      for (io.casehub.model.Capability sc : schema.getSpec().getCapabilities()) {
        Capability cap = new Capability(sc.getName(), sc.getInputSchema(), sc.getOutputSchema());
        cap.setDescription(sc.getDescription());
        capabilityMap.put(sc.getName(), cap);
        def.getCapabilities().add(cap);
      }
    }

    // Convert workers
    if (schema.getSpec().getWorkers() != null) {
      for (io.casehub.model.Worker sw : schema.getSpec().getWorkers()) {
        List<Capability> workerCaps =
            sw.getCapabilities().stream().map(capabilityMap::get).collect(Collectors.toList());

        Worker worker;
        if (sw.getAgent() != null) {
          // Convert agent from schema model to API model
          io.casehub.api.model.ai.Agent apiAgent = AgentConverter.toApiAgent(sw.getAgent());
          worker = new Worker(sw.getName(), workerCaps, apiAgent);
        } else {
          // Use workflow
          worker = new Worker(sw.getName(), workerCaps, sw.getWorkflowAsEmbedded());
        }
        worker.setDescription(sw.getDescription());
        def.getWorkers().add(worker);
      }
    }

    // Convert bindings
    if (schema.getSpec().getBindings() != null) {
      for (io.casehub.model.Binding sr : schema.getSpec().getBindings()) {
        Binding binding = convertBinding(sr, capabilityMap);
        def.getBindings().add(binding);
      }
    }

    // Convert milestones
    if (schema.getSpec().getMilestones() != null) {
      for (io.casehub.model.Milestone sm : schema.getSpec().getMilestones()) {
        Milestone milestone =
            Milestone.builder()
                .name(sm.getName())
                .completionCriteria(new JQExpressionEvaluator(sm.getCondition()))
                .build();
        milestone.setDescription(sm.getDescription());
        def.getMilestones().add(milestone);
      }
    }

    // Convert goals
    Map<String, Goal> goalMap = new java.util.LinkedHashMap<>();
    if (schema.getSpec().getGoals() != null) {
      for (io.casehub.model.Goal sg : schema.getSpec().getGoals()) {
        Goal goal =
            new Goal(sg.getName(), new JQExpressionEvaluator(sg.getCondition()), GoalKind.SUCCESS);
        goal.setDescription(sg.getDescription());
        goalMap.put(sg.getName(), goal);
        def.getGoals().add(goal);
      }
    }

    // Convert completion
    if (schema.getSpec().getCompletion() != null) {
      io.casehub.model.CaseCompletion sc = schema.getSpec().getCompletion();
      GoalExpression successExpr = convertGoalExpression(sc.getSuccess(), goalMap);
      GoalExpression failureExpr = convertGoalExpression(sc.getFailure(), goalMap);
      def.setCompletion(new GoalBasedCompletion(successExpr, failureExpr));
    }

    return def;
  }

  private static Binding convertBinding(
      io.casehub.model.Binding schemaBinding, Map<String, Capability> capabilityMap) {
    if (schemaBinding == null) {
      return null;
    }

    // Convert trigger
    io.casehub.api.model.Trigger trigger = convertTrigger(schemaBinding.getOn());

    Binding.Builder builder = Binding.builder().name(schemaBinding.getName()).on(trigger);

    // Either capability OR subCase OR humanTask (mutually exclusive)
    if (schemaBinding.getCapability() != null) {
      Capability cap = capabilityMap.get(schemaBinding.getCapability());
      if (cap == null) {
        throw new IllegalArgumentException(
            "Capability '"
                + schemaBinding.getCapability()
                + "' not found in capability map for binding '"
                + schemaBinding.getName()
                + "'");
      }
      builder.capability(cap);
    } else if (schemaBinding.getSubCase() != null) {
      io.casehub.api.model.SubCase subCase = convertSubCase(schemaBinding.getSubCase());
      builder.subCase(subCase);
    } else if (schemaBinding.getHumanTask() != null) {
      try {
        builder.humanTask(convertHumanTask(schemaBinding.getHumanTask()));
      } catch (IllegalStateException | IllegalArgumentException e) {
        throw new IllegalArgumentException(
            "Binding '" + schemaBinding.getName() + "' has invalid humanTask: " + e.getMessage(),
            e);
      }
    } else {
      throw new IllegalArgumentException(
          "Binding '" + schemaBinding.getName() + "' must have capability, subCase, or humanTask");
    }

    // Optional fields
    if (schemaBinding.getWhen() != null) {
      builder.when(new JQExpressionEvaluator(schemaBinding.getWhen()));
    }

    if (schemaBinding.getConflictResolverStrategy() != null) {
      builder.conflictResolverStrategy(schemaBinding.getConflictResolverStrategy().value());
    }

    return builder.build();
  }

  private static io.casehub.api.model.SubCase convertSubCase(io.casehub.model.SubCase schemaModel) {
    if (schemaModel == null) {
      return null;
    }

    io.casehub.api.model.SubCaseCompletionStrategy strategy =
        convertCompletionStrategy(schemaModel.getCompletionStrategy());

    return io.casehub.api.model.SubCase.builder()
        .namespace(schemaModel.getNamespace())
        .name(schemaModel.getName())
        .version(schemaModel.getVersion())
        .completionStrategy(strategy)
        .waitForCompletion(
            schemaModel.getWaitForCompletion() != null ? schemaModel.getWaitForCompletion() : true)
        .inputMapping(schemaModel.getInputMapping() != null ? schemaModel.getInputMapping() : ".")
        .outputMapping(schemaModel.getOutputMapping())
        .build();
  }

  private static io.casehub.api.model.SubCaseCompletionStrategy convertCompletionStrategy(
      io.casehub.model.SubCase.CompletionStrategy schemaStrategy) {
    if (schemaStrategy == null
        || schemaStrategy == io.casehub.model.SubCase.CompletionStrategy.DEFAULT) {
      return new io.casehub.api.model.DefaultSubCaseCompletionStrategy();
    }
    // For CUSTOM strategy, return default implementation
    return new io.casehub.api.model.DefaultSubCaseCompletionStrategy();
  }

  private static io.casehub.api.model.Trigger convertTrigger(
      io.casehub.model.Trigger schemaTrigger) {
    if (schemaTrigger == null) {
      return null;
    }

    if (schemaTrigger.getContextChange() != null) {
      String filter = schemaTrigger.getContextChange().getFilter();
      return new io.casehub.api.model.ContextChangeTrigger(
          filter != null ? new JQExpressionEvaluator(filter) : null);
    }

    // TODO: Add support for CloudEventTrigger and ScheduleTrigger
    throw new UnsupportedOperationException(
        "Only ContextChangeTrigger is currently supported. "
            + "CloudEventTrigger and ScheduleTrigger conversion not yet implemented.");
  }

  private static GoalExpression convertGoalExpression(
      io.casehub.model.GoalExpression expr, Map<String, Goal> goalMap) {
    if (expr == null) return null;

    if (expr.getAllOf() != null && !expr.getAllOf().isEmpty()) {
      List<Goal> goals = expr.getAllOf().stream().map(goalMap::get).collect(Collectors.toList());
      return new AllOfGoalExpression(goals);
    }

    if (expr.getAnyOf() != null && !expr.getAnyOf().isEmpty()) {
      List<Goal> goals = expr.getAnyOf().stream().map(goalMap::get).collect(Collectors.toList());
      return new AnyOfGoalExpression(goals);
    }

    return null;
  }

  private static HumanTaskTarget convertHumanTask(io.casehub.model.HumanTask schema) {
    HumanTaskTarget.Builder builder =
        schema.getTemplateRef() != null
            ? HumanTaskTarget.template(schema.getTemplateRef())
            : HumanTaskTarget.inline().title(schema.getTitle());

    if (schema.getInputMapping() != null) {
      builder.inputMapping(schema.getInputMapping());
    }
    if (schema.getOutputMapping() != null) {
      builder.outputMapping(schema.getOutputMapping());
    }
    if (schema.getCandidateGroups() != null && !schema.getCandidateGroups().isEmpty()) {
      builder.candidateGroups(new LinkedHashSet<>(schema.getCandidateGroups()));
    }
    if (schema.getCandidateUsers() != null && !schema.getCandidateUsers().isEmpty()) {
      builder.candidateUsers(new LinkedHashSet<>(schema.getCandidateUsers()));
    }
    if (schema.getExpiresIn() != null) {
      builder.expiresIn(Duration.parse(schema.getExpiresIn()));
    }
    return builder.build();
  }
}
