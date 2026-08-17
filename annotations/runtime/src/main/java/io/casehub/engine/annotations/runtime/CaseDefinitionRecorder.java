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
package io.casehub.engine.annotations.runtime;

import io.casehub.api.model.Binding;
import io.casehub.api.model.CaseDefinition;
import io.casehub.api.model.CaseStatus;
import io.casehub.api.model.ContextChangeTrigger;
import io.casehub.api.model.Goal;
import io.casehub.api.model.GoalBasedCompletion;
import io.casehub.api.model.GoalKind;
import io.casehub.api.model.Milestone;
import io.casehub.api.model.ScheduleTrigger;
import io.casehub.api.model.ScopeActivatedTrigger;
import io.casehub.api.model.StandardGoalKind;
import io.casehub.engine.plan.goap.GoapAction;
import io.casehub.worker.api.Capability;
import io.casehub.worker.api.Worker;
import io.quarkus.runtime.RuntimeValue;
import io.quarkus.runtime.annotations.Recorder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import org.jboss.logging.Logger;

@Recorder
public class CaseDefinitionRecorder {

  private static final Logger LOG = Logger.getLogger(CaseDefinitionRecorder.class);

  public RuntimeValue<CaseDefinition> createCaseDefinition(CaseDescriptor descriptor) {
    var builder =
        CaseDefinition.builder()
            .namespace(descriptor.namespace())
            .name(descriptor.name())
            .version(descriptor.version());

    if (descriptor.title() != null && !descriptor.title().isEmpty()) {
      builder.title(descriptor.title());
    }
    if (descriptor.summary() != null && !descriptor.summary().isEmpty()) {
      builder.summary(descriptor.summary());
    }
    if (descriptor.planningStrategy() != null) {
      builder.planningStrategy(descriptor.planningStrategy());
    }

    Map<String, Capability> capabilityMap = new HashMap<>();
    List<Worker> workers = new ArrayList<>();

    for (WorkerDescriptor wd : descriptor.workers()) {
      capabilityMap.computeIfAbsent(
          wd.capabilityName(),
          name -> Capability.builder().name(name).inputSchema(".").outputSchema(".").build());

      var workerBuilder = Worker.builder().name(wd.name()).capabilityName(wd.capabilityName());
      if (wd.params() != null && !wd.params().isEmpty() && descriptor.implClassName() != null) {
        workerBuilder.function(
            AnnotationWorkerFunction.create(
                descriptor.implClassName(),
                wd.methodName(),
                wd.params(),
                wd.returnTypeName(),
                wd.effectKey()));
      } else {
        workerBuilder.noFunction();
      }
      if (wd.description() != null && !wd.description().isEmpty()) {
        workerBuilder.description(wd.description());
      }
      workers.add(workerBuilder.build());
    }

    List<Binding> bindings = new ArrayList<>();
    for (BindingDescriptor bd : descriptor.bindings()) {
      Capability cap = capabilityMap.get(bd.capabilityName());
      if (cap == null) continue;

      var bindingBuilder = Binding.builder().name(bd.name()).capability(cap);

      if (bd.triggerType() != null) {
        switch (bd.triggerType()) {
          case "contextChange" -> bindingBuilder.on(new ContextChangeTrigger(bd.triggerValue()));
          case "cron" -> bindingBuilder.on(ScheduleTrigger.cron(bd.triggerValue()));
          case "scopeActivated" -> bindingBuilder.on(new ScopeActivatedTrigger());
          default -> bindingBuilder.on(new ContextChangeTrigger("true"));
        }
      } else {
        bindingBuilder.on(new ContextChangeTrigger("true"));
      }

      if (bd.when() != null && !bd.when().isEmpty()) {
        bindingBuilder.when(bd.when());
      }

      bindings.add(bindingBuilder.build());
    }

    builder.capabilities(new ArrayList<>(capabilityMap.values()));
    builder.workers(workers);
    builder.bindings(bindings);

    List<Goal> goals = new ArrayList<>();
    for (GoalDescriptor gd : descriptor.goals()) {
      var goalBuilder = Goal.builder().name(gd.name()).description(gd.description());

      if (gd.condition() != null && !gd.condition().isEmpty()) {
        goalBuilder.condition(gd.condition());
      }

      String kind = gd.kind() != null ? gd.kind().toLowerCase() : "success";
      switch (kind) {
        case "success" -> goalBuilder.kind(StandardGoalKind.SUCCESS);
        case "failure" -> goalBuilder.kind(StandardGoalKind.FAILURE);
        default -> goalBuilder.kind(GoalKind.of(kind, CaseStatus.COMPLETED));
      }

      goals.add(goalBuilder.build());
    }
    builder.goals(goals);

    List<Milestone> milestones = new ArrayList<>();
    for (MilestoneDescriptor md : descriptor.milestones()) {
      var milestoneBuilder = Milestone.builder().name(md.name());
      if (md.completionCriteria() != null && !md.completionCriteria().isEmpty()) {
        milestoneBuilder.completionCriteria(md.completionCriteria());
      }
      if (md.entryCriteria() != null && !md.entryCriteria().isEmpty()) {
        milestoneBuilder.entryCriteria(md.entryCriteria());
      }
      milestones.add(milestoneBuilder.build());
    }
    builder.milestones(milestones);

    if (descriptor.goapActions() != null && !descriptor.goapActions().isEmpty()) {
      List<GoapAction> goapActions = new ArrayList<>();
      for (GoapActionDescriptor gad : descriptor.goapActions()) {
        goapActions.add(
            new GoapAction(
                gad.name(),
                gad.preconditions(),
                gad.effects(),
                gad.cost(),
                gad.benefit(),
                gad.softPreconditions()));
      }
      builder.goapActions(goapActions);
    }

    if (descriptor.goalToEffectKeys() != null) {
      for (var entry : descriptor.goalToEffectKeys().entrySet()) {
        builder.goalToEffectKey(entry.getKey(), new HashSet<>(entry.getValue()));
      }
    }

    if (descriptor.completions() != null
        && !descriptor.completions().isEmpty()
        && descriptor.implClassName() != null) {
      try {
        Class<?> implClass =
            Thread.currentThread().getContextClassLoader().loadClass(descriptor.implClassName());
        Object instance = implClass.getDeclaredConstructor().newInstance();

        var completionBuilder = GoalBasedCompletion.<GoalKind>builder();
        for (CompletionDescriptor cd : descriptor.completions()) {
          java.lang.reflect.Method method = implClass.getMethod(cd.methodName());
          io.casehub.api.model.GoalExpression expression =
              (io.casehub.api.model.GoalExpression) method.invoke(instance);

          GoalKind kind =
              switch (cd.kind().toLowerCase()) {
                case "success" -> StandardGoalKind.SUCCESS;
                case "failure" -> StandardGoalKind.FAILURE;
                default -> GoalKind.of(cd.kind().toLowerCase(), CaseStatus.COMPLETED);
              };
          completionBuilder.goal(kind, expression);
        }
        builder.completion(completionBuilder.build());
      } catch (Exception e) {
        LOG.warn("Failed to wire @Completion: " + e.getMessage());
      }
    }

    if (descriptor.customizers() != null) {
      for (CustomizerDescriptor cd : descriptor.customizers()) {
        if (cd.targetBinding() == null) {
          try {
            Class<?> iface =
                Thread.currentThread().getContextClassLoader().loadClass(cd.interfaceName());
            java.lang.reflect.Method customizer =
                iface.getMethod(cd.methodName(), CaseDefinition.Builder.class);
            customizer.invoke(null, builder);
          } catch (Exception e) {
            LOG.warn("Failed to apply @Customize: " + e.getMessage());
          }
        }
      }
    }

    return new RuntimeValue<>(builder.build());
  }
}
