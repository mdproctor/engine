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
package io.casehub.engine.internal.worker;

import io.casehub.api.spi.ProvisioningException;
import io.casehub.engine.common.internal.history.EventLog;
import io.casehub.engine.common.internal.model.CaseInstance;
import io.casehub.engine.common.spi.scheduler.WorkerBackend;
import io.casehub.engine.common.spi.scheduler.WorkerExecutionManager;
import io.casehub.engine.common.spi.scheduler.WorkerExecutionRoutingStrategy;
import io.casehub.worker.api.Capability;
import io.casehub.worker.api.Worker;
import io.casehub.worker.api.WorkerFunction;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.jboss.logging.Logger;

@ApplicationScoped
public class CompositeWorkerExecutionManager implements WorkerExecutionManager {

  private static final Logger LOG = Logger.getLogger(CompositeWorkerExecutionManager.class);

  private final WorkerExecutionRoutingStrategy routingStrategy;
  private final List<WorkerExecutionManager> backends;

  @Inject
  public CompositeWorkerExecutionManager(
      WorkerExecutionRoutingStrategy routingStrategy,
      @WorkerBackend Instance<WorkerExecutionManager> discoveredBackends) {
    this.routingStrategy = routingStrategy;
    this.backends = sortByPriority(discoveredBackends);
    LOG.infof("CompositeWorkerExecutionManager initialized with %d backend(s)", backends.size());
  }

  CompositeWorkerExecutionManager(
      WorkerExecutionRoutingStrategy routingStrategy, List<WorkerExecutionManager> backends) {
    this.routingStrategy = routingStrategy;
    this.backends = List.copyOf(backends);
  }

  @Override
  public boolean supports(String capabilityName, String tenancyId) {
    for (WorkerExecutionManager backend : backends) {
      if (backend.supports(capabilityName, tenancyId)) {
        return true;
      }
    }
    return false;
  }

  @Override
  public boolean canExecute(WorkerFunction function) {
    for (WorkerExecutionManager backend : backends) {
      if (backend.canExecute(function)) return true;
    }
    return false;
  }

  @Override
  public void submit(
      Long eventLogId,
      CaseInstance instance,
      Worker worker,
      Capability capability,
      Map<String, Object> inputData) {
    if (backends.isEmpty()) {
      throw new ProvisioningException("No WorkerExecutionManager backend configured");
    }
    Optional<WorkerExecutionManager> selected =
        routingStrategy.select(backends, worker, capability, instance.tenancyId);
    if (selected.isEmpty()) {
      throw new ProvisioningException(
          "No backend supports capability '"
              + capability.name()
              + "' for tenant '"
              + instance.tenancyId
              + "'");
    }
    selected.get().submit(eventLogId, instance, worker, capability, inputData);
  }

  @Override
  public void submit(
      Long eventLogId,
      CaseInstance instance,
      Worker worker,
      Capability capability,
      Map<String, Object> inputData,
      String bindingName) {
    if (backends.isEmpty()) {
      throw new ProvisioningException("No WorkerExecutionManager backend configured");
    }
    Optional<WorkerExecutionManager> selected =
        routingStrategy.select(backends, worker, capability, instance.tenancyId);
    if (selected.isEmpty()) {
      throw new ProvisioningException(
          "No backend supports capability '"
              + capability.name()
              + "' for tenant '"
              + instance.tenancyId
              + "'");
    }
    selected.get().submit(eventLogId, instance, worker, capability, inputData, bindingName);
  }

  @Override
  public void schedulePersistedEvent(EventLog scheduledEventLog) {
    if (backends.isEmpty()) {
      return;
    }
    String capabilityName = null;
    String tenancyId = scheduledEventLog.tenancyId;
    if (scheduledEventLog.getMetadata() != null
        && scheduledEventLog.getMetadata().has("capabilityName")) {
      capabilityName = scheduledEventLog.getMetadata().get("capabilityName").asText();
    }
    if (capabilityName != null) {
      for (WorkerExecutionManager backend : backends) {
        if (backend.supports(capabilityName, tenancyId)) {
          backend.schedulePersistedEvent(scheduledEventLog);
          return;
        }
      }
      LOG.warnf(
          "No backend supports capability '%s' for schedulePersistedEvent — event may be lost",
          capabilityName);
    }
  }

  @Override
  public int getActiveWorkCount(String workerId) {
    int total = 0;
    for (WorkerExecutionManager backend : backends) {
      total += backend.getActiveWorkCount(workerId);
    }
    return total;
  }

  @Override
  public List<UUID> getActiveCaseIds(String workerId) {
    List<UUID> all = new ArrayList<>();
    for (WorkerExecutionManager backend : backends) {
      all.addAll(backend.getActiveCaseIds(workerId));
    }
    return Collections.unmodifiableList(all);
  }

  private static List<WorkerExecutionManager> sortByPriority(
      Instance<WorkerExecutionManager> instances) {
    List<WorkerExecutionManager> sorted = new ArrayList<>();
    for (WorkerExecutionManager wem : instances) {
      sorted.add(wem);
    }
    sorted.sort(
        Comparator.comparingInt(
                (WorkerExecutionManager wem) -> {
                  Class<?> realClass = wem.getClass();
                  Priority p = realClass.getAnnotation(Priority.class);
                  if (p == null && realClass.getSuperclass() != null) {
                    p = realClass.getSuperclass().getAnnotation(Priority.class);
                  }
                  return p != null ? p.value() : 0;
                })
            .reversed());
    return Collections.unmodifiableList(sorted);
  }
}
