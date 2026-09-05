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

import io.casehub.engine.common.spi.InboundWorkItemRequest;
import io.casehub.engine.common.spi.InboundWorkItemScheduler;
import io.quarkus.arc.DefaultBean;
import jakarta.enterprise.context.ApplicationScoped;
import org.jboss.logging.Logger;

/**
 * No-op default when casehub-work is not on the classpath. Logs a warning so operators know inbound
 * work items are being dropped.
 *
 * <p>Refs engine#974.
 */
@DefaultBean
@ApplicationScoped
public class NoOpInboundWorkItemScheduler implements InboundWorkItemScheduler {

  private static final Logger LOG = Logger.getLogger(NoOpInboundWorkItemScheduler.class);

  @Override
  public void schedule(InboundWorkItemRequest request) {
    LOG.warnf(
        "InboundWorkItemScheduler not available — work item '%s' for tenant '%s' dropped",
        request.title(), request.tenancyId());
  }
}
