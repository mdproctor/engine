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
package io.casehub.engine.internal.routing;

import io.casehub.api.spi.routing.HumanTaskCandidates;
import io.casehub.api.spi.routing.HumanTaskRoutingContext;
import io.casehub.api.spi.routing.HumanTaskRoutingResult;
import io.casehub.api.spi.routing.HumanTaskRoutingStrategy;
import io.quarkus.arc.DefaultBean;
import io.quarkus.arc.Unremovable;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * Default no-op humanTask routing — candidates pass through unchanged. Zero behaviour change
 * without a consumer strategy on the classpath. Protocol PP-20260514-engine-spi-noops-defaultbean.
 * Refs casehubio/engine#741.
 */
@DefaultBean
@ApplicationScoped
@Unremovable
public class NoOpHumanTaskRoutingStrategy implements HumanTaskRoutingStrategy {

  @Override
  public String id() {
    return "default";
  }

  @Override
  public Uni<HumanTaskRoutingResult> select(
      HumanTaskRoutingContext ctx, HumanTaskCandidates candidates) {
    return Uni.createFrom().item(new HumanTaskRoutingResult.Unchanged());
  }
}
