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

import io.casehub.api.model.Binding;
import io.casehub.api.model.CapabilityTarget;
import io.casehub.api.model.CaseDefinition;
import io.casehub.api.model.JudgmentTarget;
import io.casehub.api.model.Milestone;
import io.casehub.api.model.ScopeActivatedTrigger;
import io.casehub.api.model.SubCaseTarget;
import io.casehub.api.view.CoordinationPipelineView;
import io.casehub.api.view.CoordinationStepView;
import io.casehub.api.view.PhaseItemView;
import io.casehub.worker.api.Worker;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class CoordinationPipelineProjection {

  public CoordinationPipelineView project(CaseDefinition def) {
    List<Binding> bindings = def.getBindings() != null ? def.getBindings() : List.of();
    List<Milestone> milestones = def.getMilestones() != null ? def.getMilestones() : List.of();
    List<Worker> workers = def.getWorkers() != null ? def.getWorkers() : List.of();

    Set<String> agentNames = new LinkedHashSet<>();
    for (Worker w : workers) {
      agentNames.add(w.name());
    }

    int channelCount = def.getChannels() != null ? def.getChannels().size() : 0;

    List<Binding> scopeActivated = new ArrayList<>();
    List<Binding> contextTriggered = new ArrayList<>();
    for (Binding b : bindings) {
      if (b.getOn() instanceof ScopeActivatedTrigger) {
        scopeActivated.add(b);
      } else {
        contextTriggered.add(b);
      }
    }

    List<PhaseItemView> phases = new ArrayList<>();

    if (scopeActivated.size() > 1) {
      Map<String, List<CoordinationStepView>> lanes = new LinkedHashMap<>();
      for (Binding b : scopeActivated) {
        String lane = bindingLaneName(b);
        lanes.computeIfAbsent(lane, k -> new ArrayList<>()).add(bindingToStep(b));
      }
      phases.add(parallelPhase(lanes));
    } else if (scopeActivated.size() == 1) {
      phases.add(stepPhase(scopeActivated.get(0)));
    }

    for (Milestone m : milestones) {
      phases.add(signalPhase(m));
    }

    for (Binding b : contextTriggered) {
      phases.add(stepPhase(b));
    }

    return new CoordinationPipelineView(
        def.getName(),
        def.getSummary(),
        agentNames.size(),
        milestones.size(),
        channelCount,
        phases);
  }

  private PhaseItemView stepPhase(Binding b) {
    CoordinationStepView step = bindingToStep(b);
    return new PhaseItemView(
        "step",
        step.action(),
        step.target(),
        step.model(),
        step.outputFormat(),
        step.channel(),
        step.params(),
        null,
        null,
        null,
        null,
        null);
  }

  private PhaseItemView signalPhase(Milestone m) {
    return new PhaseItemView(
        "signal",
        null,
        null,
        null,
        null,
        null,
        null,
        m.getName(),
        m.getDescription(),
        null,
        null,
        null);
  }

  private PhaseItemView parallelPhase(Map<String, List<CoordinationStepView>> lanes) {
    return new PhaseItemView(
        "parallel", null, null, null, null, null, null, null, null, null, null, lanes);
  }

  private CoordinationStepView bindingToStep(Binding b) {
    String action = targetAction(b);
    String target = targetName(b);
    String channel = b.getProduces();
    return new CoordinationStepView(action, target, null, null, channel, null);
  }

  private String targetAction(Binding b) {
    return switch (b.target()) {
      case CapabilityTarget ignored -> "invoke";
      case SubCaseTarget ignored -> "subCase";
      case JudgmentTarget ignored -> "judgment";
      default -> "action";
    };
  }

  private String targetName(Binding b) {
    return switch (b.target()) {
      case CapabilityTarget ct -> ct.capability().name();
      case SubCaseTarget st -> st.subCase().name();
      case JudgmentTarget jt -> jt.title();
      default -> b.getName();
    };
  }

  private String bindingLaneName(Binding b) {
    if (b.target() instanceof CapabilityTarget ct) {
      return ct.capability().name();
    }
    return b.getName() != null ? b.getName() : "default";
  }
}
