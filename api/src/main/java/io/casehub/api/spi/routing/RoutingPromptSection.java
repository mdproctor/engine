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

import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * Pluggable SPI for composable LLM prompt enrichment in agent routing strategies.
 *
 * <p>Implementations contribute a prompt section — contextual information that helps an LLM-based
 * routing strategy make better selection decisions. All discovered implementations are composed by
 * {@link RoutingPromptAssembler} and injected into the routing prompt.
 *
 * <p>Implement as {@code @ApplicationScoped} with optional {@code @Priority(N)} to control
 * rendering order (lower values render first). Return {@code null} when the section has nothing to
 * contribute for the current context.
 *
 * <p>Implementations must be thread-safe — {@code render()} may be called concurrently.
 *
 * <p>Known implementations:
 *
 * <ul>
 *   <li>{@code CbrRoutingPromptSection} (casehub-blocks) — renders historical CBR outcomes per
 *       eligible agent
 * </ul>
 */
public interface RoutingPromptSection {

  /**
   * Render a prompt section for the given routing context and eligible candidates.
   *
   * @param context the routing context carrying caseId, capabilityName, caseContext, and tenancyId
   * @param eligible the pre-filtered, health-probed candidate list
   * @return a prompt section string, or {@code null} if this section has nothing to contribute
   */
  @Nullable String render(AgentRoutingContext context, List<AgentCandidate> eligible);
}
