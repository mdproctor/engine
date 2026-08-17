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
package io.casehub.engine.annotations.deployment;

import io.casehub.api.model.CaseDefinition;
import io.casehub.engine.annotations.PlanningMode;
import io.casehub.engine.annotations.runtime.BindingDescriptor;
import io.casehub.engine.annotations.runtime.CaseDefinitionRecorder;
import io.casehub.engine.annotations.runtime.CaseDescriptor;
import io.casehub.engine.annotations.runtime.GoalDescriptor;
import io.casehub.engine.annotations.runtime.GoapActionDescriptor;
import io.casehub.engine.annotations.runtime.MilestoneDescriptor;
import io.casehub.engine.annotations.runtime.WorkerDescriptor;
import io.quarkus.arc.deployment.SyntheticBeanBuildItem;
import io.quarkus.deployment.GeneratedClassGizmoAdaptor;
import io.quarkus.deployment.annotations.BuildProducer;
import io.quarkus.deployment.annotations.BuildStep;
import io.quarkus.deployment.annotations.ExecutionTime;
import io.quarkus.deployment.annotations.Record;
import io.quarkus.deployment.builditem.CombinedIndexBuildItem;
import io.quarkus.deployment.builditem.GeneratedClassBuildItem;
import io.quarkus.gizmo.ClassCreator;
import io.quarkus.gizmo.MethodCreator;
import io.quarkus.gizmo.MethodDescriptor;
import io.quarkus.runtime.RuntimeValue;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.jboss.jandex.AnnotationInstance;
import org.jboss.jandex.AnnotationValue;
import org.jboss.jandex.ClassInfo;
import org.jboss.jandex.DotName;
import org.jboss.jandex.IndexView;
import org.jboss.jandex.MethodInfo;
import org.jboss.jandex.MethodParameterInfo;
import org.jboss.jandex.Type;
import org.jboss.logging.Logger;

public class EngineAnnotationsProcessor {

  private static final Logger LOG = Logger.getLogger(EngineAnnotationsProcessor.class);

  private static final DotName CASE = DotName.createSimple("io.casehub.engine.annotations.Case");
  private static final DotName WORKER =
      DotName.createSimple("io.casehub.engine.annotations.Worker");
  private static final DotName BIND = DotName.createSimple("io.casehub.engine.annotations.Bind");
  private static final DotName BINDINGS =
      DotName.createSimple("io.casehub.engine.annotations.Bindings");
  private static final DotName GOAL = DotName.createSimple("io.casehub.engine.annotations.Goal");
  private static final DotName MILESTONE =
      DotName.createSimple("io.casehub.engine.annotations.Milestone");
  private static final DotName EFFECT =
      DotName.createSimple("io.casehub.engine.annotations.Effect");
  private static final DotName SOFT_DEPENDENCY =
      DotName.createSimple("io.casehub.engine.annotations.SoftDependency");
  private static final DotName PARAM = DotName.createSimple("io.casehub.engine.annotations.Param");
  private static final DotName COMPLETION =
      DotName.createSimple("io.casehub.engine.annotations.Completion");
  private static final DotName CUSTOMIZE =
      DotName.createSimple("io.casehub.engine.annotations.Customize");
  private static final DotName SYSTEM_PROMPT =
      DotName.createSimple("io.casehub.engine.annotations.SystemPrompt");

  @BuildStep
  @Record(ExecutionTime.RUNTIME_INIT)
  void generateCaseDefinitions(
      CombinedIndexBuildItem indexBuildItem,
      CaseDefinitionRecorder recorder,
      BuildProducer<SyntheticBeanBuildItem> syntheticBeans) {

    IndexView index = indexBuildItem.getIndex();

    for (AnnotationInstance caseAnn : index.getAnnotations(CASE)) {
      ClassInfo caseClass = caseAnn.target().asClass();
      CaseDescriptor descriptor = buildDescriptor(caseAnn, caseClass, index);

      RuntimeValue<CaseDefinition> runtimeValue = recorder.createCaseDefinition(descriptor);

      syntheticBeans.produce(
          SyntheticBeanBuildItem.configure(CaseDefinition.class)
              .scope(ApplicationScoped.class)
              .unremovable()
              .setRuntimeInit()
              .runtimeValue(runtimeValue)
              .done());
    }
  }

  @BuildStep
  void generateCaseImplementations(
      CombinedIndexBuildItem indexBuildItem,
      BuildProducer<GeneratedClassBuildItem> generatedClasses) {

    IndexView index = indexBuildItem.getIndex();

    for (AnnotationInstance caseAnn : index.getAnnotations(CASE)) {
      ClassInfo caseClass = caseAnn.target().asClass();
      String implClassName = caseClass.name().toString() + "_CaseHubImpl";

      try (ClassCreator creator =
          ClassCreator.builder()
              .classOutput(new GeneratedClassGizmoAdaptor(generatedClasses, true))
              .className(implClassName)
              .interfaces(caseClass.name().toString())
              .build()) {

        try (MethodCreator ctor = creator.getMethodCreator("<init>", void.class)) {
          ctor.invokeSpecialMethod(MethodDescriptor.ofConstructor(Object.class), ctor.getThis());
          ctor.returnVoid();
        }
      }
    }
  }

  private CaseDescriptor buildDescriptor(
      AnnotationInstance caseAnn, ClassInfo caseClass, IndexView index) {

    String namespace = caseAnn.value("namespace").asString();
    String name = caseAnn.value("name").asString();
    String version = stringValueOrDefault(caseAnn, index, "version", "1.0.0");
    String title = stringValueOrDefault(caseAnn, index, "title", "");
    String summary = stringValueOrDefault(caseAnn, index, "summary", "");

    PlanningMode planning =
        PlanningMode.valueOf(stringValueOrDefault(caseAnn, index, "planning", "EXPLICIT"));

    String planningStrategy = null;
    if (planning == PlanningMode.GOAP) {
      planningStrategy = "goap";
    } else if (planning == PlanningMode.ADAPTIVE) {
      planningStrategy = "adaptive";
    }

    List<WorkerDescriptor> workers = new ArrayList<>();
    List<BindingDescriptor> bindings = new ArrayList<>();
    List<GoalDescriptor> goals = new ArrayList<>();
    List<MilestoneDescriptor> milestones = new ArrayList<>();
    List<GoapActionDescriptor> goapActions = new ArrayList<>();
    Map<String, List<String>> goalToEffectKeys = new HashMap<>();
    List<io.casehub.engine.annotations.runtime.CompletionDescriptor> completions =
        new ArrayList<>();
    List<io.casehub.engine.annotations.runtime.CustomizerDescriptor> customizers =
        new ArrayList<>();

    for (MethodInfo method : caseClass.methods()) {
      AnnotationInstance workerAnn = method.annotation(WORKER);
      if (workerAnn != null) {
        processWorkerMethod(
            method, workerAnn, index, planning, workers, bindings, goapActions, goalToEffectKeys);

        AnnotationInstance systemPromptAnn = method.annotation(SYSTEM_PROMPT);
        if (systemPromptAnn != null) {
          workers.set(
              workers.size() - 1,
              new WorkerDescriptor(
                  workers.get(workers.size() - 1).name(),
                  workers.get(workers.size() - 1).capabilityName(),
                  workers.get(workers.size() - 1).description(),
                  workers.get(workers.size() - 1).methodName(),
                  workers.get(workers.size() - 1).params(),
                  workers.get(workers.size() - 1).returnTypeName(),
                  workers.get(workers.size() - 1).effectKey(),
                  systemPromptAnn.value().asString()));
        }
      }

      AnnotationInstance goalAnn = method.annotation(GOAL);
      if (goalAnn != null) {
        processGoalMethod(method, goalAnn, index, goals);
      }

      AnnotationInstance milestoneAnn = method.annotation(MILESTONE);
      if (milestoneAnn != null) {
        processMilestoneMethod(method, milestoneAnn, index, milestones);
      }

      AnnotationInstance completionAnn = method.annotation(COMPLETION);
      if (completionAnn != null) {
        String kind = stringValueOrDefault(completionAnn, index, "kind", "SUCCESS");
        completions.add(
            new io.casehub.engine.annotations.runtime.CompletionDescriptor(method.name(), kind));
      }

      AnnotationInstance customizeAnn = method.annotation(CUSTOMIZE);
      if (customizeAnn != null) {
        String targetBinding = stringValueOrDefault(customizeAnn, index, "value", "");
        customizers.add(
            new io.casehub.engine.annotations.runtime.CustomizerDescriptor(
                method.name(),
                targetBinding.isEmpty() ? null : targetBinding,
                caseClass.name().toString()));
      }
    }

    for (GoalDescriptor gd : goals) {
      if (gd.condition() != null) {
        java.util.Set<String> effectKeys =
            io.casehub.engine.annotations.runtime.GoalConditionParser.parseEffectKeys(
                gd.condition());
        if (!effectKeys.isEmpty()) {
          goalToEffectKeys.put(gd.name(), new ArrayList<>(effectKeys));
        }
      }
    }

    String implClassName = caseClass.name().toString() + "_CaseHubImpl";

    return new CaseDescriptor(
        namespace,
        name,
        version,
        title,
        summary,
        planningStrategy,
        implClassName,
        caseClass.name().toString(),
        workers,
        bindings,
        goals,
        milestones,
        goapActions.isEmpty() ? null : goapActions,
        goalToEffectKeys.isEmpty() ? null : goalToEffectKeys,
        completions.isEmpty() ? null : completions,
        customizers.isEmpty() ? null : customizers,
        null);
  }

  private void processWorkerMethod(
      MethodInfo method,
      AnnotationInstance workerAnn,
      IndexView index,
      PlanningMode planning,
      List<WorkerDescriptor> workers,
      List<BindingDescriptor> bindings,
      List<GoapActionDescriptor> goapActions,
      Map<String, List<String>> goalToEffectKeys) {

    String capabilityName = resolveCapabilityName(workerAnn, method, index);
    String description = stringValueOrDefault(workerAnn, index, "description", "");

    List<io.casehub.engine.annotations.runtime.WorkerParamDescriptor> params = new ArrayList<>();
    for (MethodParameterInfo param : method.parameters()) {
      Type paramType = param.type();
      if (paramType.name().toString().equals("io.casehub.worker.api.WorkerScope")) continue;
      String paramName = param.name() != null ? param.name() : "arg" + params.size();
      AnnotationInstance paramAnn = param.annotation(PARAM);
      String contextKey = paramAnn != null ? paramAnn.value().asString() : paramName;
      params.add(
          new io.casehub.engine.annotations.runtime.WorkerParamDescriptor(
              paramName, contextKey, paramType.name().toString()));
    }

    String returnTypeName = null;
    String effectKey = null;
    Type returnType = method.returnType();
    if (returnType.kind() != Type.Kind.VOID) {
      returnTypeName = returnType.name().toString();
      AnnotationInstance effectAnn = method.annotation(EFFECT);
      effectKey =
          effectAnn != null
              ? effectAnn.value().asString()
              : lowerCamelCase(returnType.name().local());
    }

    workers.add(
        new WorkerDescriptor(
            method.name(),
            capabilityName,
            description,
            method.name(),
            params.isEmpty() ? null : params,
            returnTypeName,
            effectKey,
            null));

    List<AnnotationInstance> bindAnns = collectBindAnnotations(method);
    if (!bindAnns.isEmpty()) {
      for (AnnotationInstance bindAnn : bindAnns) {
        bindings.add(processBindAnnotation(method, bindAnn, capabilityName, index));
      }
    } else if (planning == PlanningMode.GOAP || planning == PlanningMode.ADAPTIVE) {
      bindings.add(
          new BindingDescriptor(method.name(), capabilityName, "contextChange", "true", null));
    }

    if (planning == PlanningMode.GOAP || planning == PlanningMode.ADAPTIVE) {
      double cost =
          workerAnn.valueWithDefault(index, "cost") != null
              ? workerAnn.valueWithDefault(index, "cost").asDouble()
              : 0.0;
      double benefit =
          workerAnn.valueWithDefault(index, "benefit") != null
              ? workerAnn.valueWithDefault(index, "benefit").asDouble()
              : 0.0;
      goapActions.add(inferGoapAction(method, method.name(), cost, benefit));
    }
  }

  private BindingDescriptor processBindAnnotation(
      MethodInfo method, AnnotationInstance bindAnn, String capabilityName, IndexView index) {

    String contextChange = stringValueOrDefault(bindAnn, index, "contextChange", "");
    String cron = stringValueOrDefault(bindAnn, index, "cron", "");
    boolean scopeActivated = booleanValueOrDefault(bindAnn, index, "scopeActivated", false);
    String when = stringValueOrDefault(bindAnn, index, "when", "");

    String triggerType;
    String triggerValue;

    if (!contextChange.isEmpty()) {
      triggerType = "contextChange";
      triggerValue = contextChange;
    } else if (!cron.isEmpty()) {
      triggerType = "cron";
      triggerValue = cron;
    } else if (scopeActivated) {
      triggerType = "scopeActivated";
      triggerValue = null;
    } else {
      triggerType = "contextChange";
      triggerValue = "true";
    }

    return new BindingDescriptor(
        method.name(), capabilityName, triggerType, triggerValue, when.isEmpty() ? null : when);
  }

  private void processGoalMethod(
      MethodInfo method, AnnotationInstance goalAnn, IndexView index, List<GoalDescriptor> goals) {

    String description = goalAnn.value().asString();
    String condition = stringValueOrDefault(goalAnn, index, "condition", "");
    String kind = stringValueOrDefault(goalAnn, index, "kind", "SUCCESS");

    goals.add(
        new GoalDescriptor(
            method.name(), description, condition.isEmpty() ? null : condition, kind));
  }

  private void processMilestoneMethod(
      MethodInfo method,
      AnnotationInstance milestoneAnn,
      IndexView index,
      List<MilestoneDescriptor> milestones) {

    String name = milestoneAnn.value("name").asString();
    String completionCriteria = stringValueOrDefault(milestoneAnn, index, "completionCriteria", "");
    String entryCriteria = stringValueOrDefault(milestoneAnn, index, "entryCriteria", "");

    milestones.add(
        new MilestoneDescriptor(
            name,
            completionCriteria.isEmpty() ? null : completionCriteria,
            entryCriteria.isEmpty() ? null : entryCriteria));
  }

  private GoapActionDescriptor inferGoapAction(
      MethodInfo method, String name, double cost, double benefit) {

    Map<String, Boolean> preconditions = new HashMap<>();
    Map<String, Boolean> softPreconditions = new HashMap<>();

    for (MethodParameterInfo param : method.parameters()) {
      Type paramType = param.type();
      if (isInputParameterType(paramType)) continue;
      if (param.hasAnnotation(PARAM)) continue;

      String key = lowerCamelCase(paramType.name().local());
      if (param.hasAnnotation(SOFT_DEPENDENCY)) {
        softPreconditions.put(key, true);
      } else {
        preconditions.put(key, true);
      }
    }

    Map<String, Boolean> effects = new HashMap<>();
    Type returnType = method.returnType();
    if (returnType.kind() != Type.Kind.VOID) {
      AnnotationInstance effectAnn = method.annotation(EFFECT);
      String effectKey =
          effectAnn != null
              ? effectAnn.value().asString()
              : lowerCamelCase(returnType.name().local());
      effects.put(effectKey, true);
    }

    return new GoapActionDescriptor(name, preconditions, effects, cost, benefit, softPreconditions);
  }

  private List<AnnotationInstance> collectBindAnnotations(MethodInfo method) {
    List<AnnotationInstance> result = new ArrayList<>();
    AnnotationInstance single = method.annotation(BIND);
    if (single != null) {
      result.add(single);
    }
    AnnotationInstance container = method.annotation(BINDINGS);
    if (container != null) {
      result.clear();
      for (AnnotationInstance nested : container.value().asNestedArray()) {
        result.add(nested);
      }
    }
    return result;
  }

  private String resolveCapabilityName(
      AnnotationInstance workerAnn, MethodInfo method, IndexView index) {
    String value = stringValueOrDefault(workerAnn, index, "value", "");
    if (!value.isEmpty()) return value;
    String cap = stringValueOrDefault(workerAnn, index, "capability", "");
    if (!cap.isEmpty()) return cap;
    return method.name();
  }

  private boolean isInputParameterType(Type type) {
    String name = type.name().toString();
    return name.equals("java.lang.String")
        || name.equals("java.util.Map")
        || name.equals("int")
        || name.equals("long")
        || name.equals("double")
        || name.equals("float")
        || name.equals("boolean")
        || name.equals("byte")
        || name.equals("short")
        || name.equals("char")
        || name.equals("java.lang.Integer")
        || name.equals("java.lang.Long")
        || name.equals("java.lang.Double")
        || name.equals("java.lang.Float")
        || name.equals("java.lang.Boolean")
        || name.equals("io.casehub.worker.api.WorkerScope");
  }

  private static String lowerCamelCase(String simpleName) {
    if (simpleName == null || simpleName.isEmpty()) return simpleName;
    return Character.toLowerCase(simpleName.charAt(0)) + simpleName.substring(1);
  }

  private static String stringValueOrDefault(
      AnnotationInstance ann, IndexView index, String name, String defaultValue) {
    AnnotationValue value = ann.valueWithDefault(index, name);
    if (value == null) return defaultValue;
    String s = value.asString();
    return s != null ? s : defaultValue;
  }

  private static boolean booleanValueOrDefault(
      AnnotationInstance ann, IndexView index, String name, boolean defaultValue) {
    AnnotationValue value = ann.valueWithDefault(index, name);
    if (value == null) return defaultValue;
    return value.asBoolean();
  }
}
