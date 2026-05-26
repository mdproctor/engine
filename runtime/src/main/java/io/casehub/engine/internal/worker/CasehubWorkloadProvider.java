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

import io.casehub.engine.common.spi.scheduler.WorkerExecutionManager;
import io.casehub.work.api.WorkloadProvider;
import io.quarkus.arc.DefaultBean;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

/**
 * Counts active Quartz jobs per worker name by iterating all scheduled job groups and matching the
 * {@code workerId} field in each job's data map.
 *
 * <p>Used by {@link io.casehub.work.core.strategy.LeastLoadedStrategy} to prefer workers with fewer
 * in-flight tasks.
 */
@DefaultBean
@ApplicationScoped
public class CasehubWorkloadProvider implements WorkloadProvider {

  private static final Logger LOG = Logger.getLogger(CasehubWorkloadProvider.class);

  private final WorkerExecutionManager manager;

  @Inject
  public CasehubWorkloadProvider(WorkerExecutionManager manager) {
    this.manager = manager;
  }

  @Override
  public int getActiveWorkCount(String workerId) {
    return manager.getActiveWorkCount(workerId);
  }
}
