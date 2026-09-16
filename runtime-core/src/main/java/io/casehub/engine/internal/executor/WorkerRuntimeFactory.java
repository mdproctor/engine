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
package io.casehub.engine.internal.executor;

import io.casehub.api.engine.CaseHubRuntime;
import io.casehub.api.engine.WorkerRuntime;
import io.casehub.engine.common.spi.CaseDefinitionRegistry;
import io.casehub.engine.common.spi.cache.CaseInstanceCache;
import io.casehub.engine.internal.engine.CaseCompletionTracker;
import java.util.UUID;

public class WorkerRuntimeFactory {

  private final CaseHubRuntime caseHubRuntime;
  private final CaseDefinitionRegistry definitionRegistry;
  private final CaseInstanceCache caseInstanceCache;
  private final CaseCompletionTracker caseCompletionTracker;
  private final io.casehub.engine.common.internal.channel.DataChannelRegistry channelRegistry;
  private final io.casehub.api.spi.DataChannelFactory defaultChannelFactory;
  private final io.casehub.engine.common.internal.observation.ObservationRegistry
      observationRegistry;
  private final io.casehub.engine.common.internal.signal.SignalRegistry signalRegistry;
  private final io.casehub.engine.common.spi.PlanItemStore planItemStore;

  public WorkerRuntimeFactory(
      CaseHubRuntime caseHubRuntime,
      CaseDefinitionRegistry definitionRegistry,
      CaseInstanceCache caseInstanceCache,
      CaseCompletionTracker caseCompletionTracker,
      io.casehub.engine.common.internal.channel.DataChannelRegistry channelRegistry,
      io.casehub.api.spi.DataChannelFactory defaultChannelFactory,
      io.casehub.engine.common.internal.observation.ObservationRegistry observationRegistry,
      io.casehub.engine.common.internal.signal.SignalRegistry signalRegistry,
      io.casehub.engine.common.spi.PlanItemStore planItemStore) {
    this.caseHubRuntime = caseHubRuntime;
    this.definitionRegistry = definitionRegistry;
    this.caseInstanceCache = caseInstanceCache;
    this.caseCompletionTracker = caseCompletionTracker;
    this.channelRegistry = channelRegistry;
    this.defaultChannelFactory = defaultChannelFactory;
    this.observationRegistry = observationRegistry;
    this.signalRegistry = signalRegistry;
    this.planItemStore = planItemStore;
  }

  public WorkerRuntime create(
      UUID caseId, String taskId, io.casehub.api.model.WorkerContext context) {
    return create(caseId, taskId, context, java.util.Map.of());
  }

  public WorkerRuntime create(
      UUID caseId,
      String taskId,
      io.casehub.api.model.WorkerContext context,
      java.util.Map<String, Object> accumulatedState) {
    return new DefaultWorkerRuntime(
        caseId,
        taskId,
        context,
        accumulatedState,
        caseHubRuntime,
        definitionRegistry,
        caseInstanceCache,
        caseCompletionTracker,
        channelRegistry,
        defaultChannelFactory);
  }

  public WorkerRuntime create(
      UUID caseId,
      String taskId,
      io.casehub.api.model.WorkerContext context,
      java.util.Map<String, Object> accumulatedState,
      String workerName,
      String bindingName) {
    io.casehub.api.model.signal.SignalConfig resolvedSignalConfig = resolveSignalConfig(caseId);
    io.casehub.api.spi.observation.ObservationConfig resolvedObsConfig =
        resolveObservationConfig(caseId);

    io.casehub.api.engine.SignalSpace signalSpace =
        new io.casehub.engine.internal.signal.DefaultSignalSpace(
            signalRegistry, caseId, workerName, resolvedSignalConfig);
    io.casehub.api.engine.InterestSpace interestSpace =
        new io.casehub.engine.internal.observation.DefaultInterestSpace(
            observationRegistry, caseId, workerName, bindingName, resolvedObsConfig);

    io.casehub.api.engine.NeighborSpace neighborSpace =
        new io.casehub.engine.internal.observation.DefaultNeighborSpace(
            caseId,
            tenancyId(caseId),
            workerName,
            bindingName,
            planItemStore,
            observationRegistry,
            signalRegistry,
            definitionRegistry,
            caseInstanceCache,
            resolvedSignalConfig);

    return new DefaultWorkerRuntime(
        caseId,
        taskId,
        context,
        accumulatedState,
        caseHubRuntime,
        definitionRegistry,
        caseInstanceCache,
        caseCompletionTracker,
        channelRegistry,
        defaultChannelFactory,
        signalSpace,
        interestSpace,
        neighborSpace);
  }

  private io.casehub.api.model.signal.SignalConfig resolveSignalConfig(UUID caseId) {
    try {
      var caseInstance = caseInstanceCache.get(caseId);
      if (caseInstance != null && caseInstance.getCaseMetaModel() != null) {
        var definition =
            definitionRegistry.findByIdentity(
                caseInstance.getCaseMetaModel().getNamespace(),
                caseInstance.getCaseMetaModel().getName(),
                caseInstance.getCaseMetaModel().getVersion());
        if (definition.isPresent()) {
          var caseDef = definitionRegistry.getCaseDefinition(definition.get());
          if (caseDef != null) {
            return caseDef.getSignalConfig();
          }
        }
      }
    } catch (Exception e) {
      // Fall back to defaults on any resolution failure
    }
    return io.casehub.api.model.signal.SignalConfig.defaults();
  }

  private String tenancyId(UUID caseId) {
    try {
      var caseInstance = caseInstanceCache.get(caseId);
      return caseInstance != null ? caseInstance.tenancyId : null;
    } catch (Exception e) {
      return null;
    }
  }

  private io.casehub.api.spi.observation.ObservationConfig resolveObservationConfig(UUID caseId) {
    try {
      var caseInstance = caseInstanceCache.get(caseId);
      if (caseInstance != null && caseInstance.getCaseMetaModel() != null) {
        var definition =
            definitionRegistry.findByIdentity(
                caseInstance.getCaseMetaModel().getNamespace(),
                caseInstance.getCaseMetaModel().getName(),
                caseInstance.getCaseMetaModel().getVersion());
        if (definition.isPresent()) {
          var caseDef = definitionRegistry.getCaseDefinition(definition.get());
          if (caseDef != null) {
            return caseDef.getObservationConfig();
          }
        }
      }
    } catch (Exception e) {
      // Fall back to defaults on any resolution failure
    }
    return io.casehub.api.spi.observation.ObservationConfig.defaults();
  }
}
