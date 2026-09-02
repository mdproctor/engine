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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.casehub.api.context.JacksonPojoBridge;
import io.casehub.api.engine.ExpressionEngineRegistry;
import io.casehub.api.model.AgentWorkerFunction;
import io.casehub.api.model.Binding;
import io.casehub.api.model.CapabilityTarget;
import io.casehub.api.model.CaseDefinition;
import io.casehub.api.model.ChannelDeclaration;
import io.casehub.api.model.CognitiveDemand;
import io.casehub.api.model.CompoundDeclaration;
import io.casehub.api.model.EpisodicMemoryConfig;
import io.casehub.api.model.ExecutionMode;
import io.casehub.api.model.Goal;
import io.casehub.api.model.GoalBasedCompletion;
import io.casehub.api.model.HumanRoutingConfig;
import io.casehub.api.model.InboundSignalMapping;
import io.casehub.api.model.JudgmentTarget;
import io.casehub.api.model.LifecycleScope;
import io.casehub.api.model.MemoryRetrievalConfig;
import io.casehub.api.model.Milestone;
import io.casehub.api.model.OnThresholdReached;
import io.casehub.api.model.OutcomeAction;
import io.casehub.api.model.OutcomePolicy;
import io.casehub.api.model.OutcomeType;
import io.casehub.api.model.Participation;
import io.casehub.api.model.RecoveryLevel;
import io.casehub.api.model.RecoveryOverride;
import io.casehub.api.model.RecoveryPolicy;
import io.casehub.api.model.ReflectionTriggerConfig;
import io.casehub.api.model.ReplanHint;
import io.casehub.api.model.SideEffectClassification;
import io.casehub.api.model.SignalType;
import io.casehub.api.model.SlaStartFrom;
import io.casehub.api.model.StallRecoveryAction;
import io.casehub.api.model.StallRecoveryPolicy;
import io.casehub.api.model.SubCase;
import io.casehub.api.model.Use;
import io.casehub.api.model.WorkerFunctions;
import io.casehub.api.model.ai.Agent;
import io.casehub.api.model.converter.yaml.YamlAgentDescriptor;
import io.casehub.api.model.converter.yaml.YamlBinding;
import io.casehub.api.model.converter.yaml.YamlCapability;
import io.casehub.api.model.converter.yaml.YamlCaseDefinition;
import io.casehub.api.model.converter.yaml.YamlCaseSpec;
import io.casehub.api.model.converter.yaml.YamlChannel;
import io.casehub.api.model.converter.yaml.YamlCompound;
import io.casehub.api.model.converter.yaml.YamlContextConstraint;
import io.casehub.api.model.converter.yaml.YamlContextLayer;
import io.casehub.api.model.converter.yaml.YamlExecutionPolicy;
import io.casehub.api.model.converter.yaml.YamlGoal;
import io.casehub.api.model.converter.yaml.YamlGoapAction;
import io.casehub.api.model.converter.yaml.YamlHumanTaskTarget;
import io.casehub.api.model.converter.yaml.YamlInboundMapping;
import io.casehub.api.model.converter.yaml.YamlJudgmentTarget;
import io.casehub.api.model.converter.yaml.YamlLabelRule;
import io.casehub.api.model.converter.yaml.YamlMemoryRetrievalConfig;
import io.casehub.api.model.converter.yaml.YamlMilestone;
import io.casehub.api.model.converter.yaml.YamlMonitoringConfig;
import io.casehub.api.model.converter.yaml.YamlPlanningConstraints;
import io.casehub.api.model.converter.yaml.YamlQuorumConfig;
import io.casehub.api.model.converter.yaml.YamlRecoveryOverride;
import io.casehub.api.model.converter.yaml.YamlRecoveryPolicy;
import io.casehub.api.model.converter.yaml.YamlReflectionTriggerConfig;
import io.casehub.api.model.converter.yaml.YamlRetryPolicy;
import io.casehub.api.model.converter.yaml.YamlSignalType;
import io.casehub.api.model.converter.yaml.YamlSubCaseTarget;
import io.casehub.api.model.converter.yaml.YamlWorker;
import io.casehub.api.model.converter.yaml.YamlWorkloadConstraint;
import io.casehub.api.model.evaluator.JQExpressionEvaluator;
import io.casehub.api.model.routing.ContextConstraint;
import io.casehub.api.model.routing.WorkloadConstraint;
import io.casehub.api.spi.DiscoveredWorker;
import io.casehub.api.spi.QuorumConfig;
import io.casehub.api.spi.WorkerFunctionProviderRegistry;
import io.casehub.api.spi.routing.CandidateSetSpec;
import io.casehub.api.spi.routing.JqCandidateSetStrategy;
import io.casehub.api.spi.routing.StaticSetStrategy;
import io.casehub.eidos.api.AgentCapability;
import io.casehub.eidos.api.AgentConstraint;
import io.casehub.eidos.api.AgentDescriptor;
import io.casehub.eidos.api.AgentDisposition;
import io.casehub.eidos.api.AgentGoal;
import io.casehub.eidos.api.ConstraintSeverity;
import io.casehub.eidos.api.GoalPriority;
import io.casehub.eidos.api.Visibility;
import io.casehub.engine.plan.PlanningConstraints;
import io.casehub.engine.plan.PortfolioConfig;
import io.casehub.engine.plan.goap.GoapAction;
import io.casehub.engine.plan.monitoring.MonitoringConfig;
import io.casehub.platform.api.acl.AclAction;
import io.casehub.platform.api.expression.ExpressionEvaluator;
import io.casehub.platform.api.governance.BackoffStrategy;
import io.casehub.platform.api.governance.ExecutionPolicy;
import io.casehub.platform.api.governance.RetryPolicy;
import io.casehub.platform.api.label.LabelAction;
import io.casehub.platform.api.label.LabelRule;
import io.casehub.platform.api.path.Path;
import io.casehub.worker.api.Capability;
import io.casehub.worker.api.Worker;
import io.casehub.worker.api.WorkerFunction;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.thisptr.jackson.jq.JsonQuery;
import net.thisptr.jackson.jq.Scope;
import net.thisptr.jackson.jq.Versions;
import net.thisptr.jackson.jq.exception.JsonQueryException;
import org.jboss.logging.Logger;

/**
 * Converts YAML records to the {@link CaseDefinition} domain model. Replaces the previous
 * hand-coded deserializers and post-processor.
 */
public final class YamlCaseDefinitionConverter {

  private static final Logger LOG = Logger.getLogger(YamlCaseDefinitionConverter.class);
  private static final ObjectMapper MAPPER = new ObjectMapper();

  private YamlCaseDefinitionConverter() {}

  record HtnLeafTask(String id, String description, String capabilityName)
      implements io.casehub.engine.plan.TaskNode.LeafTask<com.fasterxml.jackson.databind.JsonNode> {
    @Override
    public io.casehub.api.model.ExecutorRef executor() {
      return null;
    }

    @Override
    public io.casehub.api.model.TaskStatus status() {
      return io.casehub.api.model.TaskStatus.PENDING;
    }

    @Override
    public java.time.Instant createdAt() {
      return java.time.Instant.EPOCH;
    }
  }

  public static CaseDefinition convert(
      YamlCaseDefinition yaml,
      ExpressionEngineRegistry registry,
      WorkerFunctionProviderRegistry providers) {
    CaseDefinition def = new CaseDefinition(yaml.namespace(), yaml.name(), yaml.version());

    convertTopLevel(yaml, def);

    Map<String, CapabilityTarget> capTargetMap = Map.of();
    if (yaml.spec() != null) {
      capTargetMap = convertSpec(yaml.spec(), def);
    }

    List<YamlWorker> effectiveWorkers =
        yaml.spec() != null && !yaml.spec().workers().isEmpty()
            ? yaml.spec().workers()
            : yaml.workers();
    convertWorkers(effectiveWorkers, def, providers);

    List<YamlBinding> effectiveBindings =
        yaml.spec() != null && !yaml.spec().bindings().isEmpty()
            ? yaml.spec().bindings()
            : yaml.bindings();
    convertBindings(effectiveBindings, def, capTargetMap);
    java.util.List<String> compensationErrors =
        Binding.validateCompensationBindings(def.getBindings());
    if (!compensationErrors.isEmpty()) {
      throw new IllegalArgumentException(
          "Compensation binding validation failed: " + String.join("; ", compensationErrors));
    }

    convertLabelRules(yaml.labelRules(), def);
    convertInboundMappings(yaml.inboundMappings(), def);

    if (yaml.spec() != null && yaml.spec().decomposition() != null) {
      convertDecomposition(yaml.spec().decomposition(), def, registry);
    }

    if (!yaml.definitions().isEmpty()) {
      def.setDefinitions(yaml.definitions());
    }

    linkGoalKinds(def);
    applyContextTypeBridge(def);

    return def;
  }

  // --- Top-level fields ---------------------------------------------------

  private static void convertTopLevel(YamlCaseDefinition yaml, CaseDefinition def) {
    def.setDsl(yaml.dsl());
    def.setTitle(yaml.title());
    def.setSummary(yaml.summary());
    if (yaml.expressionLang() != null) {
      def.setExpressionLang(yaml.expressionLang());
    }
    if (yaml.contextType() != null) {
      def.setContextType(yaml.contextType());
      if (yaml.expressionLang() == null) {
        def.setExpressionLang("mvel");
      }
    }
    if (yaml.context() != null && yaml.context().has("storeFactory")) {
      def.setContextStoreFactory(yaml.context().get("storeFactory").asText());
    }
    if (yaml.semanticData() != null) {
      def.setSemanticData(yaml.semanticData());
    }

    if (!yaml.types().isEmpty()) {
      Set<Path> types = new LinkedHashSet<>();
      for (String t : yaml.types()) {
        types.add(Path.parse(t));
      }
      def.setTypes(types);
    }
    if (!yaml.labels().isEmpty()) {
      Set<Path> labels = new LinkedHashSet<>();
      for (String l : yaml.labels()) {
        labels.add(Path.parse(l));
      }
      def.setLabels(labels);
    }

    if (!yaml.signals().isEmpty()) {
      List<SignalType<?>> signals = new ArrayList<>();
      for (YamlSignalType sig : yaml.signals()) {
        Class<?> payloadType = Map.class;
        if (sig.contextType() != null) {
          try {
            payloadType = Class.forName(sig.contextType());
          } catch (ClassNotFoundException e) {
            throw new IllegalArgumentException(
                "Signal '" + sig.name() + "' contextType class not found: " + sig.contextType(), e);
          }
        }
        signals.add(SignalType.of(sig.name(), payloadType));
      }
      def.setSignals(signals);
    }

    if (yaml.episodic() != null && yaml.episodic().has("memory")) {
      JsonNode memNode = yaml.episodic().get("memory");
      String domain = memNode.has("domain") ? memNode.get("domain").asText() : null;
      String entityId = memNode.has("entityId") ? memNode.get("entityId").asText() : null;
      int recent = memNode.has("recent") ? memNode.get("recent").asInt() : 10;
      def.setEpisodicMemoryConfig(EpisodicMemoryConfig.of(domain, entityId, recent));
    }

    if (!yaml.layers().isEmpty()) {
      List<String> layerNames = new ArrayList<>();
      for (YamlContextLayer yl : yaml.layers()) {
        if (yl.name() != null) {
          layerNames.add(yl.name());
        }
      }
      def.setLayerNames(layerNames);
    }

    if (yaml.use() != null && !yaml.use().isNull()) {
      def.setUse(MAPPER.convertValue(yaml.use(), Use.class));
    }
  }

  // --- Spec ---------------------------------------------------------------

  private static Map<String, CapabilityTarget> convertSpec(YamlCaseSpec spec, CaseDefinition def) {
    Map<String, CapabilityTarget> capTargetMap = convertCapabilities(spec.capabilities(), def);

    for (YamlGoal yg : spec.goals()) {
      Goal goal = new Goal(yg.name(), yg.when(), yg.kind());
      if (yg.description() != null) {
        goal.setDescription(yg.description());
      }
      def.getGoals().add(goal);
    }

    for (YamlMilestone ym : spec.milestones()) {
      def.getMilestones().add(convertMilestone(ym));
    }

    if (spec.completion() != null) {
      def.setCompletion(spec.completion());
    }

    if (spec.planningStrategy() != null) def.setPlanningStrategy(spec.planningStrategy());
    if (spec.agentRouting() != null) def.setAgentRouting(spec.agentRouting());
    if (spec.implementationRouting() != null)
      def.setImplementationRouting(spec.implementationRouting());
    if (spec.humanTaskRouting() != null) def.setHumanTaskRouting(spec.humanTaskRouting());
    if (spec.candidateMatching() != null) def.setCandidateMatching(spec.candidateMatching());
    if (spec.decompositionStrategy() != null)
      def.setDecompositionStrategy(spec.decompositionStrategy());
    if (spec.maxDecompositionDepth() != null)
      def.setMaxDecompositionDepth(spec.maxDecompositionDepth());
    if (spec.maxAdaptations() != null) def.setMaxAdaptations(spec.maxAdaptations());
    if (spec.maxEscalations() != null) def.setMaxEscalations(spec.maxEscalations());

    if (spec.cbrConfig() != null) def.setCbrConfig(spec.cbrConfig());
    if (spec.adaptationConfig() != null) def.setAdaptationConfig(spec.adaptationConfig());
    if (spec.recoveryPolicy() != null)
      def.setRecoveryPolicy(convertRecoveryPolicy(spec.recoveryPolicy()));
    if (spec.stallRecoveryPolicy() != null)
      def.setStallRecoveryPolicy(convertStallRecoveryPolicy(spec.stallRecoveryPolicy()));
    if (spec.monitoring() != null) def.setMonitoringConfig(convertMonitoring(spec.monitoring()));
    if (spec.reflectionTrigger() != null)
      def.setReflectionTrigger(convertReflection(spec.reflectionTrigger()));
    if (spec.memoryRetrieval() != null)
      def.setMemoryRetrieval(convertMemoryRetrieval(spec.memoryRetrieval()));
    if (spec.quorum() != null) def.setDefaultQuorum(convertQuorum(spec.quorum()));
    if (spec.planningConstraints() != null)
      def.setPlanningConstraints(convertPlanningConstraints(spec.planningConstraints()));
    if (spec.portfolioConfig() != null)
      def.setPortfolioConfig(MAPPER.convertValue(spec.portfolioConfig(), PortfolioConfig.class));

    if (!spec.humanTaskContextConstraints().isEmpty()) {
      List<ContextConstraint> constraints = new ArrayList<>();
      for (YamlContextConstraint ycc : spec.humanTaskContextConstraints()) {
        constraints.add(convertContextConstraint(ycc));
      }
      def.setHumanTaskContextConstraints(constraints);
    }
    if (spec.humanTaskWorkloadConstraint() != null) {
      def.setHumanTaskWorkloadConstraint(
          convertWorkloadConstraint(spec.humanTaskWorkloadConstraint()));
    }
    if (!spec.routingSignalWeights().isEmpty()) {
      def.setRoutingSignalWeights(spec.routingSignalWeights());
    }
    if (!spec.workerServiceAccountIds().isEmpty()) {
      def.setWorkerServiceAccountIds(spec.workerServiceAccountIds());
    }
    if (!spec.cognitiveDemands().isEmpty()) {
      Map<String, CognitiveDemand> demands = new LinkedHashMap<>();
      spec.cognitiveDemands().forEach((k, v) -> demands.put(k, new CognitiveDemand(v.weights())));
      def.setCognitiveDemands(demands);
    }
    if (!spec.authorization().isEmpty()) {
      Map<AclAction, List<String>> auth = new LinkedHashMap<>();
      spec.authorization().forEach((k, v) -> auth.put(AclAction.valueOf(k.toUpperCase()), v));
      def.setAuthorization(auth);
    }
    if (!spec.actions().isEmpty()) {
      List<GoapAction> actions = new ArrayList<>();
      for (YamlGoapAction ya : spec.actions()) {
        actions.add(convertGoapAction(ya));
      }
      def.setGoapActions(actions);
    }
    if (!spec.compounds().isEmpty()) {
      List<CompoundDeclaration> compounds = new ArrayList<>();
      for (YamlCompound yc : spec.compounds()) {
        compounds.add(convertCompound(yc));
      }
      def.setCompounds(compounds);
    }
    if (!spec.channels().isEmpty()) {
      List<ChannelDeclaration> channels = new ArrayList<>();
      for (YamlChannel ych : spec.channels()) {
        channels.add(convertChannel(ych));
      }
      def.setChannels(channels);
    }
    if (!spec.layers().isEmpty()) {
      List<String> layerNames = new ArrayList<>();
      for (YamlContextLayer yl : spec.layers()) {
        if (yl.name() != null) {
          layerNames.add(yl.name());
        }
      }
      def.setLayerNames(layerNames);
    }

    return capTargetMap;
  }

  // --- Capabilities -------------------------------------------------------

  private static Map<String, CapabilityTarget> convertCapabilities(
      List<YamlCapability> caps, CaseDefinition def) {
    Map<String, CapabilityTarget> capTargetMap = new LinkedHashMap<>();
    Map<String, CognitiveDemand> cognitiveDemands = new LinkedHashMap<>();

    for (YamlCapability yc : caps) {
      String inputProj = yc.inputProjection() != null ? yc.inputProjection() : ".";
      String outputProj = yc.outputProjection() != null ? yc.outputProjection() : ".";

      Capability cap =
          Capability.builder()
              .name(yc.name())
              .inputSchema(inputProj)
              .outputSchema(outputProj)
              .description(yc.description())
              .build();
      def.getCapabilities().add(cap);

      capTargetMap.put(
          yc.name(),
          new CapabilityTarget(
              cap, new JQExpressionEvaluator(inputProj), new JQExpressionEvaluator(outputProj)));

      if (!yc.cognitiveDemand().isEmpty()) {
        cognitiveDemands.put(yc.name(), new CognitiveDemand(yc.cognitiveDemand()));
      }
    }

    if (!cognitiveDemands.isEmpty()) {
      def.setCognitiveDemands(cognitiveDemands);
    }
    return capTargetMap;
  }

  // --- Milestones ---------------------------------------------------------

  private static Milestone convertMilestone(YamlMilestone ym) {
    Milestone.Builder b = Milestone.builder();
    if (ym.name() != null) b.name(ym.name());
    if (ym.description() != null) b.description(ym.description());
    if (ym.when() != null) b.completionCriteria(ym.when());
    if (ym.entryCriteria() != null) b.entryCriteria(ym.entryCriteria());
    if (ym.slaDuration() != null) {
      String raw = ym.slaDuration();
      try {
        Duration d = Duration.parse(raw);
        if (d.isZero() || d.isNegative()) {
          throw new IllegalArgumentException(
              "Milestone '" + ym.name() + "' slaDuration must be positive, got: " + raw);
        }
        b.slaDuration(d);
      } catch (java.time.format.DateTimeParseException e) {
        throw new IllegalArgumentException(
            "Milestone '" + ym.name() + "' has invalid slaDuration: " + raw, e);
      }
    }
    if (ym.slaStartFrom() != null) {
      b.slaStartFrom(SlaStartFrom.valueOf(ym.slaStartFrom()));
    }
    if (ym.sla() != null && ym.sla().duration() != null && ym.slaDuration() == null) {
      b.slaDuration(Duration.parse(ym.sla().duration()));
    }
    return b.build();
  }

  // --- Workers ------------------------------------------------------------

  private static void convertWorkers(
      List<YamlWorker> yamlWorkers, CaseDefinition def, WorkerFunctionProviderRegistry providers) {
    Map<String, Worker> workerIndex = new LinkedHashMap<>();
    List<String> sequenceWorkerNames = new ArrayList<>();

    for (YamlWorker yw : yamlWorkers) {
      if (yw.name() == null) continue;

      Worker.Builder builder =
          Worker.builder()
              .name(yw.name())
              .capabilityNames(new LinkedHashSet<>(yw.capabilities()))
              .noFunction();
      if (yw.description() != null) builder.description(yw.description());
      if (yw.definitionRef() != null) builder.definitionRef(yw.definitionRef());
      if (yw.executionPolicy() != null) {
        builder.executionPolicy(convertExecutionPolicy(yw.executionPolicy()));
      }
      workerIndex.put(yw.name(), builder.build());

      if (!yw.sequence().isEmpty()) {
        sequenceWorkerNames.add(yw.name());
      }
    }

    if (providers != null) {
      Map<String, Worker> builtWorkers = new LinkedHashMap<>(workerIndex);

      for (YamlWorker yw : yamlWorkers) {
        if (yw.name() == null || sequenceWorkerNames.contains(yw.name())) continue;

        JsonNode rawWorker = MAPPER.valueToTree(yw);

        List<DiscoveredWorker> discovered = providers.discoverWorkers(rawWorker);
        if (!discovered.isEmpty()) {
          builtWorkers.remove(yw.name());
          for (DiscoveredWorker dw : discovered) {
            Worker dWorker =
                Worker.builder()
                    .name(dw.workerName())
                    .capabilityName(dw.capability().name())
                    .function(dw.function())
                    .build();
            builtWorkers.put(dw.workerName(), dWorker);
          }
          continue;
        }

        WorkerFunction<?, ?> function = providers.createFunction(rawWorker);
        if (function == null) {
          if (yw.agent() != null) {
            function = buildAgentFunction(yw);
          } else if (yw.contextType() != null) {
            function = buildTypedSyncFunction(yw);
          } else {
            function = WorkerFunction.NONE;
          }
        }

        Worker existing = workerIndex.get(yw.name());
        if (existing != null) {
          Worker updated =
              Worker.builder()
                  .name(existing.name())
                  .capabilityNames(existing.capabilities())
                  .function(function)
                  .executionPolicy(existing.executionPolicy())
                  .description(existing.description())
                  .definitionRef(existing.definitionRef())
                  .build();
          builtWorkers.put(yw.name(), updated);
        }
      }

      for (String seqName : sequenceWorkerNames) {
        YamlWorker seqWorker =
            yamlWorkers.stream().filter(w -> seqName.equals(w.name())).findFirst().orElse(null);
        if (seqWorker == null) continue;

        List<WorkerFunction> stepFunctions = new ArrayList<>();
        for (String stepName : seqWorker.sequence()) {
          Worker stepWorker = builtWorkers.get(stepName);
          if (stepWorker == null) {
            throw new IllegalArgumentException(
                "Worker '" + seqName + "' sequence references unknown worker '" + stepName + "'");
          }
          stepFunctions.add(stepWorker.function());
        }
        @SuppressWarnings("unchecked")
        WorkerFunction<?, ?> sequenceFunc =
            WorkerFunctions.sequence(stepFunctions.toArray(new WorkerFunction[0]));

        Worker existing = workerIndex.get(seqName);
        if (existing != null) {
          Worker updated =
              Worker.builder()
                  .name(existing.name())
                  .capabilityNames(existing.capabilities())
                  .function(sequenceFunc)
                  .executionPolicy(existing.executionPolicy())
                  .description(existing.description())
                  .definitionRef(existing.definitionRef())
                  .build();
          builtWorkers.put(seqName, updated);
        }
      }

      def.getWorkers().addAll(builtWorkers.values());
    } else {
      def.getWorkers().addAll(workerIndex.values());
    }

    applyGoapShorthand(yamlWorkers, def);
    applyAgentDescriptors(yamlWorkers, def);
  }

  private static WorkerFunction<?, ?> buildAgentFunction(YamlWorker yw) {
    try {
      JsonNode agentNode = MAPPER.valueToTree(yw.agent());
      Agent agent = AgentConverter.toApiAgent(agentNode);
      return new AgentWorkerFunction(agent);
    } catch (Exception e) {
      LOG.warnf(
          "Worker '%s': agent conversion failed, falling back to NONE — %s",
          yw.name(), e.getMessage());
      return WorkerFunction.NONE;
    }
  }

  private static WorkerFunction<?, ?> buildTypedSyncFunction(YamlWorker yw) {
    try {
      Class<?> contextClass = Class.forName(yw.contextType());
      Class<?> outType = yw.outputType() != null ? Class.forName(yw.outputType()) : Map.class;
      String name = yw.name();
      return new WorkerFunction.Sync<>(
          contextClass,
          outType,
          (input, scope) -> {
            throw new UnsupportedOperationException(
                "YAML-declared contextType worker '"
                    + name
                    + "' has no in-process function — dispatch via external backend");
          });
    } catch (ClassNotFoundException e) {
      throw new IllegalArgumentException(
          "Worker '" + yw.name() + "' type class not found: " + yw.contextType(), e);
    }
  }

  private static ExecutionPolicy convertExecutionPolicy(YamlExecutionPolicy yep) {
    RetryPolicy retries = null;
    if (yep.retries() != null) {
      YamlRetryPolicy yr = yep.retries();
      BackoffStrategy backoff =
          yr.backoffStrategy() != null
              ? BackoffStrategy.valueOf(yr.backoffStrategy())
              : BackoffStrategy.FIXED;
      retries = new RetryPolicy(yr.maxAttempts(), yr.delayMs(), backoff, yr.maxDelayMs());
    }
    return retries != null
        ? new ExecutionPolicy(yep.timeoutMs(), retries)
        : new ExecutionPolicy(yep.timeoutMs(), new RetryPolicy());
  }

  // --- GOAP shorthand -----------------------------------------------------

  private static void applyGoapShorthand(List<YamlWorker> yamlWorkers, CaseDefinition def) {
    List<GoapAction> workerGoapActions = new ArrayList<>();
    for (YamlWorker yw : yamlWorkers) {
      if (yw.effect().isEmpty() && yw.cost() == null) continue;
      String capName = !yw.capabilities().isEmpty() ? yw.capabilities().get(0) : yw.name();
      if (capName == null) continue;

      Map<String, Boolean> softPrec = Map.of();
      if (!yw.softDependency().isEmpty()) {
        Map<String, Boolean> sp = new LinkedHashMap<>();
        yw.softDependency().forEach(e -> sp.put(e, true));
        softPrec = Map.copyOf(sp);
      }
      double cost = yw.cost() != null ? yw.cost() : 1.0;
      workerGoapActions.add(new GoapAction(capName, Map.of(), yw.effect(), cost, 0, softPrec));
    }
    if (!workerGoapActions.isEmpty()) {
      List<GoapAction> existing =
          def.getGoapActions() != null ? new ArrayList<>(def.getGoapActions()) : new ArrayList<>();
      existing.addAll(workerGoapActions);
      def.setGoapActions(existing);
    }
  }

  // --- Agent descriptors --------------------------------------------------

  private static void applyAgentDescriptors(List<YamlWorker> yamlWorkers, CaseDefinition def) {
    Map<String, AgentDescriptor> descriptors = new LinkedHashMap<>();
    for (YamlWorker yw : yamlWorkers) {
      if (yw.name() == null || yw.agentDescriptor() == null) continue;
      descriptors.put(yw.name(), convertAgentDescriptor(yw.agentDescriptor(), yw.name()));
    }
    if (!descriptors.isEmpty()) {
      def.setAgentDescriptors(descriptors);
    }
  }

  static AgentDescriptor convertAgentDescriptor(YamlAgentDescriptor yad, String workerName) {
    var builder = AgentDescriptor.builder();
    builder.agentId(yad.agentId() != null ? yad.agentId() : workerName);
    builder.name(yad.name() != null ? yad.name() : workerName);
    builder.slot(yad.slot() != null ? yad.slot() : workerName);
    builder.tenancyId(yad.tenancyId() != null ? yad.tenancyId() : "default");

    if (yad.briefing() != null) builder.briefing(yad.briefing());
    if (yad.version() != null) builder.version(yad.version());
    if (yad.provider() != null) builder.provider(yad.provider());
    if (yad.modelFamily() != null) builder.modelFamily(yad.modelFamily());
    if (yad.modelVersion() != null) builder.modelVersion(yad.modelVersion());
    if (yad.jurisdiction() != null) builder.jurisdiction(yad.jurisdiction());
    if (yad.dataHandlingPolicy() != null) builder.dataHandlingPolicy(yad.dataHandlingPolicy());

    if (!yad.goals().isEmpty()) {
      List<AgentGoal> goals = new ArrayList<>();
      for (YamlAgentDescriptor.YamlAgentGoal yag : yad.goals()) {
        String priority = yag.priority() != null ? yag.priority() : "PRIMARY";
        String visibility = yag.visibility() != null ? yag.visibility() : "PUBLIC";
        goals.add(
            new AgentGoal(
                yag.name(),
                yag.description() != null ? yag.description() : yag.name(),
                GoalPriority.valueOf(priority),
                Visibility.valueOf(visibility),
                yag.capabilities(),
                yag.attributes()));
      }
      builder.goals(goals);
    }

    if (!yad.constraints().isEmpty()) {
      List<AgentConstraint> constraints = new ArrayList<>();
      for (YamlAgentDescriptor.YamlAgentConstraint yac : yad.constraints()) {
        constraints.add(
            new AgentConstraint(
                yac.name(),
                yac.description() != null ? yac.description() : yac.name(),
                yac.visibility() != null ? Visibility.valueOf(yac.visibility()) : Visibility.PUBLIC,
                yac.severity() != null
                    ? ConstraintSeverity.valueOf(yac.severity())
                    : ConstraintSeverity.HARD));
      }
      builder.constraints(constraints);
    }

    if (yad.disposition() != null) {
      YamlAgentDescriptor.YamlAgentDisposition yd = yad.disposition();
      var db = AgentDisposition.builder();
      if (yd.socialOrient() != null) db.socialOrient(yd.socialOrient());
      if (yd.ruleFollowing() != null) db.ruleFollowing(yd.ruleFollowing());
      if (yd.riskAppetite() != null) db.riskAppetite(yd.riskAppetite());
      if (yd.autonomy() != null) db.autonomy(yd.autonomy());
      if (yd.conflictMode() != null) db.conflictMode(yd.conflictMode());
      if (yd.delegation() != null) db.delegation(yd.delegation());
      builder.disposition(db.build());
    }

    if (!yad.capabilities().isEmpty()) {
      List<AgentCapability> caps = new ArrayList<>();
      for (YamlAgentDescriptor.YamlAgentCapability yac : yad.capabilities()) {
        var cb = AgentCapability.builder();
        cb.name(yac.name());
        if (yac.description() != null) cb.description(yac.description());
        if (yac.qualityHint() != null) cb.qualityHint(yac.qualityHint());
        if (yac.latencyHintP50Ms() != null) cb.latencyHintP50Ms(yac.latencyHintP50Ms());
        if (yac.costHint() != null) cb.costHint(yac.costHint());
        if (!yac.tags().isEmpty()) cb.tags(yac.tags());
        caps.add(cb.build());
      }
      builder.capabilities(caps);
    }

    return builder.build();
  }

  // --- Bindings -----------------------------------------------------------

  private static void convertBindings(
      List<YamlBinding> yamlBindings,
      CaseDefinition def,
      Map<String, CapabilityTarget> capTargetMap) {
    for (YamlBinding yb : yamlBindings) {
      Binding.Builder builder = Binding.builder().name(yb.name()).on(yb.on());

      resolveTarget(yb, builder, capTargetMap);

      if (yb.when() != null) builder.when(yb.when());
      if (yb.inputProjectionOverride() != null) {
        builder.inputProjectionOverride(yb.inputProjectionOverride());
      }
      if (yb.conflictResolverStrategy() != null) {
        builder.conflictResolverStrategy(yb.conflictResolverStrategy());
      }
      if (yb.lifecycleScope() != null) {
        builder.lifecycleScope(LifecycleScope.valueOf(yb.lifecycleScope()));
      }
      if (yb.participation() != null) {
        builder.participation(Participation.valueOf(yb.participation()));
      }
      if (yb.executionMode() != null) {
        builder.executionMode(ExecutionMode.valueOf(yb.executionMode()));
      }
      if (yb.replanHint() != null) {
        builder.replanHint(ReplanHint.valueOf(yb.replanHint().toUpperCase()));
      }
      if (yb.outcomePolicy() != null && !yb.outcomePolicy().isNull()) {
        builder.outcomePolicy(convertOutcomePolicy(yb.outcomePolicy()));
      }
      if (!yb.contextWrite().isEmpty()) {
        builder.contextWrite(yb.contextWrite());
      }
      if (!yb.producedKeys().isEmpty()) {
        builder.producedKeys(new LinkedHashSet<>(yb.producedKeys()));
      }
      if (!yb.contingency().isEmpty()) {
        builder.contingency(yb.contingency());
      }

      String epExpression = null;
      if (yb.exchangeProjection() != null) {
        JsonNode ep = yb.exchangeProjection();
        if (ep.isTextual()) {
          builder.exchangeProjectionStrategy(ep.asText());
        } else if (ep.isObject()) {
          if (ep.has("strategy")) builder.exchangeProjectionStrategy(ep.get("strategy").asText());
          if (ep.has("expression")) epExpression = ep.get("expression").asText();
        }
      }
      if (yb.produces() != null) builder.produces(yb.produces());
      if (yb.consumes() != null) builder.consumes(yb.consumes());

      if (yb.sideEffectClassification() != null) {
        builder.sideEffectClassification(
            SideEffectClassification.valueOf(yb.sideEffectClassification()));
      }
      if (yb.recoveryOverride() != null) {
        builder.recoveryOverride(convertRecoveryOverride(yb.recoveryOverride()));
      }
      if (yb.compensate() != null) {
        builder.compensateRef(yb.compensate());
      }
      if (yb.compensation() != null && yb.compensation()) {
        builder.compensation(true);
      }
      if (!yb.permissionIntent().isEmpty()) {
        List<io.casehub.platform.api.acl.WorkerAction> actions = new ArrayList<>();
        for (String pi : yb.permissionIntent()) {
          actions.add(io.casehub.api.acl.EngineWorkerActions.fromKebabCase(pi));
        }
        builder.permissionIntent(actions);
      }

      Binding result = builder.build();
      if (epExpression != null) {
        result.setExchangeProjectionExpression(epExpression);
      }
      def.getBindings().add(result);
    }
  }

  private static void resolveTarget(
      YamlBinding yb, Binding.Builder builder, Map<String, CapabilityTarget> capTargetMap) {
    if (yb.capability() != null) {
      String capName = yb.capability();
      if (capTargetMap.containsKey(capName)) {
        builder.target(capTargetMap.get(capName));
      } else {
        builder.capability(Capability.of(capName, ".", "."));
      }
    } else if (yb.subCase() != null) {
      builder.subCase(convertSubCase(yb.subCase()));
    } else if (yb.humanTask() != null) {
      builder.judgment(convertHumanTaskToJudgment(yb.humanTask(), yb.name()));
    } else if (yb.judgment() != null) {
      builder.judgment(convertJudgment(yb.judgment(), yb.name()));
    } else if (yb.signal() != null) {
      builder.signal(yb.signal());
    }
  }

  private static SubCase convertSubCase(YamlSubCaseTarget ys) {
    SubCase.Builder b = SubCase.builder();
    if (ys.namespace() != null) b.namespace(ys.namespace());
    if (ys.name() != null) b.name(ys.name());
    if (ys.version() != null) b.version(ys.version());
    if (ys.waitForCompletion() != null) b.waitForCompletion(ys.waitForCompletion());
    if (ys.maxRecursionDepth() != null) b.maxRecursionDepth(ys.maxRecursionDepth());
    if (ys.inputMapping() != null) b.inputMapping(ys.inputMapping());
    if (ys.outputMapping() != null) b.outputMapping(ys.outputMapping());
    if (ys.groupId() != null) b.groupId(ys.groupId());
    if (ys.totalInGroup() != null) b.totalInGroup(ys.totalInGroup());
    if (ys.requiredCount() != null) b.requiredCount(ys.requiredCount());
    if (ys.onThresholdReached() != null) {
      b.onThresholdReached(OnThresholdReached.valueOf(ys.onThresholdReached()));
    }
    return b.build();
  }

  private static JudgmentTarget convertHumanTaskToJudgment(
      YamlHumanTaskTarget ht, String bindingName) {
    boolean hasTemplate = ht.templateRef() != null;
    boolean hasTitle = ht.title() != null;
    if (hasTemplate && hasTitle) {
      throw new IllegalArgumentException(
          "Binding '"
              + bindingName
              + "' cannot specify both 'title' and 'templateRef' on humanTask");
    }

    JudgmentTarget.Builder jb = JudgmentTarget.builder();

    if (hasTitle) {
      jb.prompt(ht.title());
      jb.title(ht.title());
    } else if (!hasTemplate) {
      jb.prompt(bindingName);
    } else {
      jb.prompt(bindingName);
    }
    if (ht.titleExpression() != null) {
      validateExpression(ht.titleExpression(), "titleExpression", bindingName);
      jb.titleExpression(ht.titleExpression());
    }
    if (ht.expiresIn() != null) {
      String raw = ht.expiresIn();
      try {
        java.time.Duration d = java.time.Duration.parse(raw);
        if (d.isZero() || d.isNegative()) {
          throw new IllegalArgumentException(
              "Binding '" + bindingName + "' humanTask expiresIn must be positive, got: " + raw);
        }
        jb.expiresIn(d);
      } catch (java.time.format.DateTimeParseException e) {
        throw new IllegalArgumentException(
            "Binding '" + bindingName + "' humanTask has invalid expiresIn: " + raw, e);
      }
    }
    if (ht.expiresInExpression() != null) {
      if (ht.expiresIn() != null) {
        throw new IllegalArgumentException(
            "Binding '"
                + bindingName
                + "' humanTask cannot specify both 'expiresIn' and 'expiresInExpression'");
      }
      ExpressionEvaluator eiExpr = ht.expiresInExpression();
      if (eiExpr instanceof JQExpressionEvaluator jq
          && (jq.expression() == null || jq.expression().isBlank())) {
        // blank/whitespace treated as not set
      } else {
        validateExpression(eiExpr, "expiresInExpression", bindingName);
        jb.expiresInExpression(eiExpr);
      }
    }
    if (ht.expiresAtExpression() != null) {
      validateExpression(ht.expiresAtExpression(), "expiresAtExpression", bindingName);
      jb.expiresAtExpression(ht.expiresAtExpression());
    }
    if (ht.priority() != null) {
      jb.priority(ht.priority());
    }
    if (ht.inputMapping() != null) {
      jb.inputMapping(ht.inputMapping());
    }
    if (ht.outputMapping() != null) {
      jb.outputMapping(ht.outputMapping());
    }
    if (ht.scope() != null) {
      jb.scope(ht.scope());
    }
    if (ht.scopeExpression() != null) {
      jb.scopeExpression(ht.scopeExpression());
    }
    if (!ht.outcomes().isEmpty()) {
      jb.outcomes(ht.outcomes());
    }
    if (ht.resolutionType() != null) {
      try {
        jb.resolutionType(Class.forName(ht.resolutionType()));
      } catch (ClassNotFoundException e) {
        throw new IllegalArgumentException(
            "humanTask resolutionType class not found: " + ht.resolutionType(), e);
      }
    }

    CandidateSetSpec candidateGroups = resolveCandidateSet(ht.candidateGroups());
    CandidateSetSpec candidateUsers = resolveCandidateSet(ht.candidateUsers());
    String templateRef = ht.templateRef();
    Integer claimDeadlineHours = ht.claimDeadlineHours();
    Class<?> payloadType = null;
    if (ht.payloadType() != null) {
      try {
        payloadType = Class.forName(ht.payloadType());
      } catch (ClassNotFoundException e) {
        throw new IllegalArgumentException(
            "humanTask payloadType class not found: " + ht.payloadType(), e);
      }
    }
    jb.human(
        new HumanRoutingConfig(
            templateRef, candidateGroups, candidateUsers, claimDeadlineHours, payloadType));

    try {
      return jb.build();
    } catch (IllegalStateException e) {
      throw new IllegalArgumentException("Binding '" + bindingName + "' " + e.getMessage(), e);
    }
  }

  private static JudgmentTarget convertJudgment(YamlJudgmentTarget yj, String bindingName) {
    JudgmentTarget.Builder b = JudgmentTarget.builder();
    if (yj.prompt() != null) b.prompt(yj.prompt());
    if (yj.promptExpression() != null) b.promptExpression(yj.promptExpression());
    if (yj.inputMapping() != null) b.inputMapping(yj.inputMapping());
    if (yj.outputMapping() != null) b.outputMapping(yj.outputMapping());
    if (yj.resolutionType() != null) {
      try {
        b.resolutionType(Class.forName(yj.resolutionType()));
      } catch (ClassNotFoundException e) {
        throw new IllegalArgumentException(
            "Binding '"
                + bindingName
                + "' judgment has unknown resolutionType: "
                + yj.resolutionType(),
            e);
      }
    }
    if (yj.expiresIn() != null) {
      Duration d = Duration.parse(yj.expiresIn());
      if (d.isZero() || d.isNegative()) {
        throw new IllegalArgumentException(
            "Binding '"
                + bindingName
                + "' judgment expiresIn must be positive, got: "
                + yj.expiresIn());
      }
      b.expiresIn(d);
    }
    if (yj.expiresInExpression() != null) b.expiresInExpression(yj.expiresInExpression());
    if (yj.expiresAtExpression() != null) b.expiresAtExpression(yj.expiresAtExpression());
    if (!yj.evidenceRequirements().isEmpty()) b.evidenceRequirements(yj.evidenceRequirements());
    if (yj.verifierStrategy() != null) b.verifierStrategy(yj.verifierStrategy());
    if (yj.escalationStrategy() != null) b.escalatorStrategy(yj.escalationStrategy());
    if (yj.trustThreshold() != null) b.trustThreshold(yj.trustThreshold());
    if (yj.title() != null) b.title(yj.title());
    if (yj.titleExpression() != null) b.titleExpression(yj.titleExpression());
    if (!yj.outcomes().isEmpty()) b.outcomes(yj.outcomes());
    if (yj.scope() != null) b.scope(yj.scope());
    if (yj.scopeExpression() != null) b.scopeExpression(yj.scopeExpression());
    if (yj.priority() != null) b.priority(yj.priority());
    if (yj.maxEscalationAttempts() != null) b.maxEscalationAttempts(yj.maxEscalationAttempts());

    if (yj.human() != null) {
      JsonNode humanNode = yj.human();
      CandidateSetSpec cg =
          humanNode.has("candidateGroups")
              ? resolveCandidateSet(humanNode.get("candidateGroups"))
              : null;
      CandidateSetSpec cu =
          humanNode.has("candidateUsers")
              ? resolveCandidateSet(humanNode.get("candidateUsers"))
              : null;
      String templateRef =
          humanNode.has("templateRef") ? humanNode.get("templateRef").asText() : null;
      Integer claimDeadlineHours =
          humanNode.has("claimDeadlineHours") ? humanNode.get("claimDeadlineHours").asInt() : null;
      Class<?> payloadType = null;
      if (humanNode.has("payloadType")) {
        try {
          payloadType = Class.forName(humanNode.get("payloadType").asText());
        } catch (ClassNotFoundException e) {
          throw new IllegalArgumentException(
              "Binding '"
                  + bindingName
                  + "' judgment human payloadType not found: "
                  + humanNode.get("payloadType").asText(),
              e);
        }
      }
      b.human(new HumanRoutingConfig(templateRef, cg, cu, claimDeadlineHours, payloadType));
    }

    return b.build();
  }

  private static CandidateSetSpec resolveCandidateSet(JsonNode node) {
    if (node == null || node.isNull()) {
      return null;
    }
    if (node.isArray()) {
      Set<String> items = new LinkedHashSet<>();
      for (JsonNode n : node) {
        if (!n.isTextual()) {
          throw new IllegalArgumentException(
              "candidateGroups elements must be strings, got: " + n.getNodeType());
        }
        items.add(n.asText());
      }
      if (items.isEmpty()) {
        return null;
      }
      return new CandidateSetSpec.Inline(StaticSetStrategy.of(items));
    } else if (node.isTextual()) {
      return new CandidateSetSpec.Inline(new JqCandidateSetStrategy(node.asText()));
    }
    return null;
  }

  private static void validateExpression(
      ExpressionEvaluator evaluator, String fieldName, String bindingName) {
    if (evaluator instanceof JQExpressionEvaluator jq) {
      try {
        JQExpressionEvaluator.validate(jq.expression());
      } catch (Exception e) {
        throw new IllegalArgumentException(
            "Binding '" + bindingName + "' has invalid " + fieldName + ": " + e.getMessage(), e);
      }
    }
  }

  private static OutcomePolicy convertOutcomePolicy(JsonNode node) {
    OutcomeAction onDecline =
        node.has("onDecline")
            ? OutcomeAction.valueOf(node.get("onDecline").asText())
            : OutcomeAction.REROUTE;
    OutcomeAction onFailure =
        node.has("onFailure")
            ? OutcomeAction.valueOf(node.get("onFailure").asText())
            : OutcomeAction.REROUTE;
    OutcomeAction onExpired =
        node.has("onExpired")
            ? OutcomeAction.valueOf(node.get("onExpired").asText())
            : OutcomeAction.REROUTE;
    int maxAttempts = node.has("maxRerouteAttempts") ? node.get("maxRerouteAttempts").asInt() : 3;
    return new OutcomePolicy(onDecline, onFailure, onExpired, maxAttempts);
  }

  private static RecoveryOverride convertRecoveryOverride(YamlRecoveryOverride yro) {
    Set<OutcomeType> skipFor = new HashSet<>();
    if (yro.skipRecoveryFor() != null) {
      yro.skipRecoveryFor().forEach(s -> skipFor.add(OutcomeType.valueOf(s)));
    }
    return new RecoveryOverride(
        yro.maxRetries(),
        yro.maxRerouteAttempts(),
        yro.maxLevel() != null ? RecoveryLevel.valueOf(yro.maxLevel()) : null,
        yro.skipRecovery() != null && yro.skipRecovery(),
        skipFor);
  }

  // --- Config conversions -------------------------------------------------

  private static RecoveryPolicy convertRecoveryPolicy(YamlRecoveryPolicy yrp) {
    return MAPPER.convertValue(yrp, RecoveryPolicy.class);
  }

  private static StallRecoveryPolicy convertStallRecoveryPolicy(
      com.fasterxml.jackson.databind.JsonNode node) {
    boolean enabled = node.path("enabled").asBoolean(false);
    String classifierId =
        node.has("classifierId") ? node.get("classifierId").asText() : "policy-lookup";
    StallRecoveryAction defaultAction = StallRecoveryAction.NOTIFY;
    if (node.has("defaultAction")) {
      defaultAction = StallRecoveryAction.valueOf(node.get("defaultAction").asText().toUpperCase());
    }
    java.util.Map<io.casehub.qhorus.api.watchdog.WatchdogConditionType, StallRecoveryAction>
        conditionActions =
            new java.util.EnumMap<>(io.casehub.qhorus.api.watchdog.WatchdogConditionType.class);
    com.fasterxml.jackson.databind.JsonNode ca = node.get("conditionActions");
    if (ca != null && ca.isObject()) {
      ca.fields()
          .forEachRemaining(
              entry -> {
                var ct =
                    io.casehub.qhorus.api.watchdog.WatchdogConditionType.valueOf(entry.getKey());
                var action = StallRecoveryAction.valueOf(entry.getValue().asText().toUpperCase());
                conditionActions.put(ct, action);
              });
    }
    return new StallRecoveryPolicy(enabled, classifierId, conditionActions, defaultAction);
  }

  private static MonitoringConfig convertMonitoring(YamlMonitoringConfig ym) {
    boolean enabled = ym.enabled() != null ? ym.enabled() : true;
    double threshold =
        ym.perCompletionThreshold() != null
            ? ym.perCompletionThreshold()
            : MonitoringConfig.DEFAULT_THRESHOLD;
    int windowSize =
        ym.windowSize() != null ? ym.windowSize() : MonitoringConfig.DEFAULT_WINDOW_SIZE;
    return new MonitoringConfig(enabled, threshold, windowSize);
  }

  private static ReflectionTriggerConfig convertReflection(YamlReflectionTriggerConfig yr) {
    boolean enabled = yr.enabled() != null ? yr.enabled() : false;
    double threshold = yr.importanceThreshold() != null ? yr.importanceThreshold() : 3.0;
    int maxUnreflected = yr.maxUnreflectedOutcomes() != null ? yr.maxUnreflectedOutcomes() : 10;
    int maxSource = yr.maxSourceMemories() != null ? yr.maxSourceMemories() : 50;
    Map<String, Double> weights = ReflectionTriggerConfig.DEFAULT_IMPORTANCE_WEIGHTS;
    if (yr.importanceWeights() != null && !yr.importanceWeights().isEmpty()) {
      weights = yr.importanceWeights();
    }
    return new ReflectionTriggerConfig(enabled, threshold, maxUnreflected, maxSource, weights);
  }

  private static MemoryRetrievalConfig convertMemoryRetrieval(YamlMemoryRetrievalConfig ym) {
    return MAPPER.convertValue(ym, MemoryRetrievalConfig.class);
  }

  private static QuorumConfig convertQuorum(YamlQuorumConfig yq) {
    int instances = yq.instances() != null ? yq.instances() : 3;
    int required = yq.required() != null ? yq.required() : 2;
    OnThresholdReached otr = null;
    if (yq.onThresholdReached() != null) {
      try {
        otr = OnThresholdReached.valueOf(yq.onThresholdReached());
      } catch (IllegalArgumentException e) {
        throw new IllegalArgumentException(
            "Invalid onThresholdReached value: "
                + yq.onThresholdReached()
                + ". Valid values: "
                + java.util.Arrays.toString(OnThresholdReached.values()),
            e);
      }
    }
    boolean allowSameAssignee = yq.allowSameAssignee() != null && yq.allowSameAssignee();
    return new QuorumConfig(instances, required, otr, allowSameAssignee);
  }

  private static PlanningConstraints convertPlanningConstraints(YamlPlanningConstraints ypc) {
    Duration timeBudget = ypc.timeBudget() != null ? Duration.parse(ypc.timeBudget()) : null;
    Map<String, Double> weights = ypc.weights() != null ? ypc.weights() : Map.of();
    Map<String, Integer> costBudgets = ypc.costBudgets() != null ? ypc.costBudgets() : Map.of();
    return new PlanningConstraints(timeBudget, ypc.resourceLimit(), weights, costBudgets);
  }

  private static ContextConstraint convertContextConstraint(YamlContextConstraint ycc) {
    var b = ContextConstraint.builder();
    if (ycc.when() != null) b.when(ycc.when());
    if (ycc.weight() != null) b.weight(ycc.weight());
    if (ycc.effect() != null) {
      var effect = ycc.effect();
      boolean hasPrefer =
          (effect.preferGroups() != null && !effect.preferGroups().isEmpty())
              || (effect.preferUsers() != null && !effect.preferUsers().isEmpty());
      boolean hasExclude =
          (effect.excludeGroups() != null && !effect.excludeGroups().isEmpty())
              || (effect.excludeUsers() != null && !effect.excludeUsers().isEmpty());
      if (hasPrefer) {
        Set<String> groups =
            effect.preferGroups() != null ? new LinkedHashSet<>(effect.preferGroups()) : Set.of();
        Set<String> users =
            effect.preferUsers() != null ? new LinkedHashSet<>(effect.preferUsers()) : Set.of();
        b.prefer(groups, users);
      } else if (hasExclude) {
        Set<String> groups =
            effect.excludeGroups() != null ? new LinkedHashSet<>(effect.excludeGroups()) : Set.of();
        Set<String> users =
            effect.excludeUsers() != null ? new LinkedHashSet<>(effect.excludeUsers()) : Set.of();
        b.exclude(groups, users);
      }
    }
    return b.build();
  }

  private static WorkloadConstraint convertWorkloadConstraint(YamlWorkloadConstraint ywc) {
    var b = WorkloadConstraint.builder();
    if (ywc.maxActiveTaskCount() != null) b.maxActiveTaskCount(ywc.maxActiveTaskCount());
    if (ywc.loadBalanceWeight() != null) b.loadBalanceWeight(ywc.loadBalanceWeight());
    return b.build();
  }

  private static GoapAction convertGoapAction(YamlGoapAction ya) {
    double cost = ya.cost() != null ? ya.cost() : 1.0;
    double benefit = ya.benefit() != null ? ya.benefit() : 0.0;
    return new GoapAction(
        ya.name(), ya.preconditions(), ya.effects(), cost, benefit, ya.softPreconditions());
  }

  private static CompoundDeclaration convertCompound(YamlCompound yc) {
    Map<String, Participation> scopedBindings = new LinkedHashMap<>();
    if (!yc.scopedBindings().isEmpty()) {
      yc.scopedBindings().forEach((k, v) -> scopedBindings.put(k, Participation.valueOf(v)));
    }
    return new CompoundDeclaration(
        yc.name(),
        yc.completionSemantics(),
        yc.dispatchMode(),
        scopedBindings,
        yc.entryCondition(),
        yc.exitCondition(),
        yc.repeatable() != null && yc.repeatable(),
        yc.planningStrategy());
  }

  private static ChannelDeclaration convertChannel(YamlChannel ych) {
    Class<?> rt = Map.class;
    if (ych.recordType() != null) {
      try {
        rt = Class.forName(ych.recordType());
      } catch (ClassNotFoundException e) {
        throw new IllegalArgumentException(
            "Channel '" + ych.name() + "' recordType not found: " + ych.recordType(), e);
      }
    }
    LifecycleScope scope = ych.scope() != null ? LifecycleScope.valueOf(ych.scope()) : null;
    return new ChannelDeclaration(ych.name(), rt, ych.transport(), scope);
  }

  // --- HTN decomposition ---------------------------------------------------

  private static void convertDecomposition(
      io.casehub.api.model.converter.yaml.YamlDecomposition decomp,
      CaseDefinition def,
      ExpressionEngineRegistry registry) {
    if (decomp.root() == null) return;

    var root = convertCompoundNode(decomp.root(), registry);
    def.setDecompositionTree(root);

    if (def.getDecompositionStrategy() == null) {
      def.setDecompositionStrategy("explicit");
    }
  }

  private static io.casehub.engine.plan.TaskNode.CompoundTask<
          com.fasterxml.jackson.databind.JsonNode>
      convertCompoundNode(
          io.casehub.api.model.converter.yaml.YamlHtnNode node, ExpressionEngineRegistry registry) {
    java.util.List<
            io.casehub.engine.plan.DecompositionMethod<com.fasterxml.jackson.databind.JsonNode>>
        methods = node.methods().stream().map(m -> convertHtnMethod(m, registry)).toList();
    return new io.casehub.engine.plan.TaskNode.CompoundTask<>(node.name(), methods);
  }

  private static io.casehub.engine.plan.DecompositionMethod<com.fasterxml.jackson.databind.JsonNode>
      convertHtnMethod(
          io.casehub.api.model.converter.yaml.YamlHtnMethod method,
          ExpressionEngineRegistry registry) {
    java.util.function.Predicate<com.fasterxml.jackson.databind.JsonNode> guard =
        method.guard() != null ? state -> registry.evaluate(method.guard(), state) : state -> true;

    io.casehub.engine.plan.DecompositionStrategy<com.fasterxml.jackson.databind.JsonNode> strategy =
        (task, ctx) -> {
          java.util.List<
                  io.casehub.engine.plan.TaskNode.LeafTask<com.fasterxml.jackson.databind.JsonNode>>
              leaves = new java.util.ArrayList<>();
          for (var childNode : method.tasks()) {
            if (childNode.isLeaf()) {
              leaves.add(
                  new HtnLeafTask(
                      java.util.UUID.randomUUID().toString(),
                      childNode.description() != null ? childNode.description() : childNode.name(),
                      childNode.capability()));
            } else {
              var nestedCompound = convertCompoundNode(childNode, registry);
              var nestedStrategy =
                  nestedCompound.methods().stream()
                      .filter(m -> m.guard().test(ctx.state()))
                      .findFirst()
                      .orElseThrow(
                          () ->
                              new IllegalStateException(
                                  "No matching method for compound '" + childNode.name() + "'"));
              var nestedPlan = nestedStrategy.strategy().decompose(nestedCompound, ctx);
              for (var n : nestedPlan.nodes().values()) {
                leaves.add(n.task());
              }
            }
          }
          return io.casehub.engine.plan.DagPlan.sequence(leaves);
        };

    java.time.Duration estimatedDuration =
        method.estimatedDuration() != null
            ? java.time.Duration.parse(method.estimatedDuration())
            : null;

    return new io.casehub.engine.plan.DecompositionMethod<>(
        method.name(),
        guard,
        strategy,
        method.guardLabel(),
        method.estimatedCost().isEmpty() ? null : method.estimatedCost(),
        estimatedDuration);
  }

  // --- Label rules --------------------------------------------------------

  private static void convertLabelRules(List<YamlLabelRule> yamlRules, CaseDefinition def) {
    if (yamlRules.isEmpty()) return;
    List<LabelRule> rules = new ArrayList<>();
    for (YamlLabelRule yr : yamlRules) {
      io.casehub.platform.api.expression.CompiledExpression<Map<String, Object>, Boolean>
          condition = null;
      if (yr.when() != null) {
        condition = new JqLabelRuleCondition(yr.when());
      }
      List<LabelAction> actions = new ArrayList<>();
      if (yr.actions() != null) {
        for (var ya : yr.actions()) {
          if (ya.add() != null) {
            actions.add(new LabelAction.Add(ya.add()));
          } else if (ya.remove() != null) {
            actions.add(new LabelAction.Remove(ya.remove()));
          }
        }
      }
      rules.add(new LabelRule(yr.name(), condition, actions));
    }
    def.setLabelRules(Collections.unmodifiableList(rules));
  }

  // --- Inbound mappings ---------------------------------------------------

  private static void convertInboundMappings(
      List<YamlInboundMapping> yamlMappings, CaseDefinition def) {
    if (yamlMappings.isEmpty()) return;
    List<InboundSignalMapping> mappings = new ArrayList<>();
    for (YamlInboundMapping ym : yamlMappings) {
      var b = InboundSignalMapping.builder();
      if (ym.signal() != null) b.signalName(ym.signal());
      if (ym.connectorType() != null) b.connectorType(ym.connectorType());
      if (ym.correlation() != null) b.correlation(ym.correlation());
      if (ym.payload() != null) b.payload(ym.payload());
      if (ym.correlationResolver() != null) b.correlationResolver(ym.correlationResolver());
      mappings.add(b.build());
    }
    def.setInboundMappings(mappings);
  }

  // --- Goal kind linking --------------------------------------------------

  private static void linkGoalKinds(CaseDefinition def) {
    if (!(def.getCompletion() instanceof GoalBasedCompletion<?> gbc)) return;
    for (var entry : gbc.getGoals().entrySet()) {
      String kindName = entry.getKey().value();
      Set<String> goalNames = entry.getValue().goalNames();
      for (int i = 0; i < def.getGoals().size(); i++) {
        Goal g = def.getGoals().get(i);
        if (goalNames.contains(g.getName()) && g.getKind() == null) {
          Goal updated = new Goal(g.getName(), g.getCondition(), kindName);
          if (g.getDescription() != null) updated.setDescription(g.getDescription());
          def.getGoals().set(i, updated);
        }
      }
    }
  }

  // --- Context type bridge ------------------------------------------------

  private static void applyContextTypeBridge(CaseDefinition def) {
    String contextType = def.getContextType();
    if (contextType == null || contextType.isBlank()) return;
    try {
      Class<?> contextClass = Class.forName(contextType);
      def.setDefaultWorkerBridge(new JacksonPojoBridge<>(contextClass));
    } catch (ClassNotFoundException e) {
      throw new IllegalArgumentException(
          "CaseDefinition '" + def.getName() + "' contextType class not found: " + contextType, e);
    }
  }

  // --- Helpers ------------------------------------------------------------

  private static Map<String, Boolean> toTrueBooleanMap(List<String> keys) {
    Map<String, Boolean> map = new LinkedHashMap<>();
    keys.forEach(k -> map.put(k, true));
    return Map.copyOf(map);
  }

  // --- JQ label rule condition (ported from CaseDefinitionDeserializer) ----

  static final class JqLabelRuleCondition
      implements io.casehub.platform.api.expression.CompiledExpression<
          Map<String, Object>, Boolean> {
    private final ExpressionEvaluator evaluator;
    private final JsonQuery compiledQuery;

    JqLabelRuleCondition(ExpressionEvaluator evaluator) {
      this.evaluator = evaluator;
      if (evaluator instanceof JQExpressionEvaluator jq) {
        try {
          this.compiledQuery = JsonQuery.compile(jq.expression(), Versions.JQ_1_6);
        } catch (JsonQueryException e) {
          throw new IllegalArgumentException("Invalid JQ in label rule: " + jq.expression(), e);
        }
      } else {
        this.compiledQuery = null;
      }
    }

    @Override
    public String type() {
      return evaluator.type();
    }

    @Override
    public Boolean eval(Map<String, Object> context) {
      if (compiledQuery != null) {
        try {
          JsonNode input = MAPPER.valueToTree(context);
          Scope scope = Scope.newEmptyScope();
          List<JsonNode> results = new ArrayList<>();
          compiledQuery.apply(scope, input, results::add);
          if (results.isEmpty()) return false;
          JsonNode result = results.get(0);
          return result.isBoolean()
              ? result.asBoolean()
              : !result.isNull() && !result.isMissingNode();
        } catch (Exception e) {
          return false;
        }
      }
      throw new UnsupportedOperationException(
          "LabelRule evaluation for '"
              + evaluator.type()
              + "' requires runtime ExpressionEngineRegistry");
    }
  }
}
