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

import io.quarkus.deployment.annotations.BuildStep;
import io.quarkus.deployment.annotations.Produce;
import io.quarkus.deployment.builditem.CombinedIndexBuildItem;
import io.quarkus.deployment.builditem.ServiceStartBuildItem;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.jboss.jandex.AnnotationInstance;
import org.jboss.jandex.AnnotationValue;
import org.jboss.jandex.ClassInfo;
import org.jboss.jandex.DotName;
import org.jboss.jandex.IndexView;
import org.jboss.jandex.MethodInfo;
import org.jboss.logging.Logger;

public class AnnotationValidationStep {

  private static final Logger LOG = Logger.getLogger(AnnotationValidationStep.class);

  private static final DotName CASE = DotName.createSimple("io.casehub.engine.annotations.Case");
  private static final DotName WORKER =
      DotName.createSimple("io.casehub.engine.annotations.Worker");
  private static final DotName BIND = DotName.createSimple("io.casehub.engine.annotations.Bind");
  private static final DotName BINDINGS =
      DotName.createSimple("io.casehub.engine.annotations.Bindings");
  private static final DotName GOAL = DotName.createSimple("io.casehub.engine.annotations.Goal");
  private static final DotName MILESTONE =
      DotName.createSimple("io.casehub.engine.annotations.Milestone");
  private static final DotName SYSTEM_PROMPT =
      DotName.createSimple("io.casehub.engine.annotations.SystemPrompt");
  private static final DotName COMPLETION =
      DotName.createSimple("io.casehub.engine.annotations.Completion");
  private static final DotName SOFT_DEPENDENCY =
      DotName.createSimple("io.casehub.engine.annotations.SoftDependency");

  @BuildStep
  @Produce(ServiceStartBuildItem.class)
  void validate(CombinedIndexBuildItem indexBuildItem) {
    IndexView index = indexBuildItem.getIndex();
    List<String> errors = new ArrayList<>();
    List<String> warnings = new ArrayList<>();

    for (AnnotationInstance caseAnn : index.getAnnotations(CASE)) {
      ClassInfo caseClass = caseAnn.target().asClass();
      String planning = stringOr(caseAnn, index, "planning", "EXPLICIT");
      boolean isGoap = "GOAP".equals(planning) || "ADAPTIVE".equals(planning);

      Set<String> goalNames = new HashSet<>();
      Set<String> milestoneNames = new HashSet<>();
      Set<String> completionKinds = new HashSet<>();

      for (MethodInfo method : caseClass.methods()) {
        validateWorkerMethod(method, index, errors, warnings, isGoap);
        validateBindAnnotations(method, index, errors);
        validateGoal(method, goalNames, errors);
        validateMilestone(method, index, milestoneNames, errors);
        validateCompletion(method, index, completionKinds, errors);
        validateSystemPromptConflict(method, errors);
      }
    }

    for (String warning : warnings) {
      LOG.warn(warning);
    }

    if (!errors.isEmpty()) {
      throw new RuntimeException("Annotation validation failed:\n- " + String.join("\n- ", errors));
    }
  }

  private void validateWorkerMethod(
      MethodInfo method,
      IndexView index,
      List<String> errors,
      List<String> warnings,
      boolean isGoap) {
    AnnotationInstance workerAnn = method.annotation(WORKER);
    if (workerAnn == null) return;

    String loc = method.declaringClass().name() + "#" + method.name();

    String capability = stringOr(workerAnn, index, "capability", "");
    AnnotationValue capsValue = workerAnn.value("capabilities");
    String[] capabilities = capsValue != null ? capsValue.asStringArray() : new String[0];
    if (!capability.isEmpty() && capabilities.length > 0) {
      errors.add(loc + ": @Worker sets both 'capability' and 'capabilities'");
    }

    for (var param : method.parameters()) {
      if (param.name() != null && param.name().matches("arg\\d+")) {
        errors.add(
            loc + ": parameter '" + param.name() + "' has synthetic name — add -parameters flag");
        break;
      }
    }

    if (!isGoap) {
      for (var param : method.parameters()) {
        if (param.hasAnnotation(SOFT_DEPENDENCY)) {
          warnings.add(loc + ": @SoftDependency has no effect in EXPLICIT planning mode");
          break;
        }
      }
    }

    if (method.annotation(SYSTEM_PROMPT) != null && method.hasAnnotation(WORKER)) {
      if (method.returnType().kind() != org.jboss.jandex.Type.Kind.VOID) {
        warnings.add(loc + ": @SystemPrompt worker with return type — return value is unused");
      }
    }
  }

  private void validateBindAnnotations(MethodInfo method, IndexView index, List<String> errors) {
    List<AnnotationInstance> bindAnns = collectBindAnnotations(method);
    String loc = method.declaringClass().name() + "#" + method.name();

    for (AnnotationInstance bind : bindAnns) {
      int triggerCount = 0;
      if (!stringOr(bind, index, "contextChange", "").isEmpty()) triggerCount++;
      if (!stringOr(bind, index, "cron", "").isEmpty()) triggerCount++;
      if (boolOr(bind, index, "scopeActivated", false)) triggerCount++;

      if (triggerCount == 0) {
        errors.add(loc + ": @Bind has no trigger — set contextChange, cron, or scopeActivated");
      }
      if (triggerCount > 1) {
        errors.add(loc + ": @Bind has multiple triggers — set exactly one");
      }
    }
  }

  private void validateGoal(MethodInfo method, Set<String> goalNames, List<String> errors) {
    AnnotationInstance goalAnn = method.annotation(GOAL);
    if (goalAnn == null) return;
    if (!goalNames.add(method.name())) {
      errors.add(method.declaringClass().name() + ": duplicate @Goal name '" + method.name() + "'");
    }
  }

  private void validateMilestone(
      MethodInfo method, IndexView index, Set<String> milestoneNames, List<String> errors) {
    AnnotationInstance milestoneAnn = method.annotation(MILESTONE);
    if (milestoneAnn == null) return;
    String name = milestoneAnn.value("name").asString();
    if (!milestoneNames.add(name)) {
      errors.add(method.declaringClass().name() + ": duplicate @Milestone name '" + name + "'");
    }
  }

  private void validateCompletion(
      MethodInfo method, IndexView index, Set<String> completionKinds, List<String> errors) {
    AnnotationInstance completionAnn = method.annotation(COMPLETION);
    if (completionAnn == null) return;
    String kind = stringOr(completionAnn, index, "kind", "SUCCESS");
    if (!completionKinds.add(kind)) {
      errors.add(
          method.declaringClass().name()
              + "#"
              + method.name()
              + ": duplicate @Completion kind '"
              + kind
              + "'");
    }
  }

  private void validateSystemPromptConflict(MethodInfo method, List<String> errors) {
    if (method.annotation(SYSTEM_PROMPT) != null && !method.hasAnnotation(WORKER)) {
      errors.add(
          method.declaringClass().name()
              + "#"
              + method.name()
              + ": @SystemPrompt requires @Worker on the same method");
    }
  }

  private List<AnnotationInstance> collectBindAnnotations(MethodInfo method) {
    List<AnnotationInstance> result = new ArrayList<>();
    AnnotationInstance single = method.annotation(BIND);
    if (single != null) result.add(single);
    AnnotationInstance container = method.annotation(BINDINGS);
    if (container != null) {
      result.clear();
      for (AnnotationInstance nested : container.value().asNestedArray()) {
        result.add(nested);
      }
    }
    return result;
  }

  private static String stringOr(AnnotationInstance ann, IndexView index, String name, String def) {
    AnnotationValue v = ann.valueWithDefault(index, name);
    return v != null ? v.asString() : def;
  }

  private static boolean boolOr(AnnotationInstance ann, IndexView index, String name, boolean def) {
    AnnotationValue v = ann.valueWithDefault(index, name);
    return v != null ? v.asBoolean() : def;
  }
}
