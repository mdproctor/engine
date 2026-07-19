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
package io.casehub.api.spi.routing;

import io.casehub.platform.api.routing.NamedStrategy;
import io.smallrye.mutiny.Uni;

/**
 * Pluggable strategy for enriching humanTask candidate sets with historical data. Symmetric with
 * {@link AgentRoutingStrategy} — follows the routing strategy convention (engine#634): {@code
 * select()} method, context/candidates separation, sealed result type.
 *
 * <p>The strategy receives pre-resolved candidate groups/users and retrieved CBR experiences, and
 * returns an enriched or unchanged result. The default implementation ({@code
 * NoOpHumanTaskRoutingStrategy}) returns {@link HumanTaskRoutingResult.Unchanged}.
 *
 * <p>Resolved via {@code StrategyResolver} from {@code CaseDefinition.getHumanTaskRouting()}.
 */
public interface HumanTaskRoutingStrategy extends NamedStrategy {
  Uni<HumanTaskRoutingResult> select(
      HumanTaskRoutingContext context, HumanTaskCandidates candidates);
}
