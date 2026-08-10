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

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import java.util.Comparator;
import java.util.List;
import org.jboss.logging.Logger;
import org.jspecify.annotations.Nullable;

/**
 * Discovers all {@link RoutingPromptSection} implementations via CDI, sorts them by {@link
 * jakarta.annotation.Priority} (lower values first), and assembles their rendered output into a
 * single prompt string.
 *
 * <p>Sections returning {@code null} or blank strings are skipped. Sections that throw are logged
 * and skipped — a failing section never prevents other sections from rendering.
 *
 * <p>Non-null results are joined with double newlines ({@code \n\n}).
 */
@ApplicationScoped
public class RoutingPromptAssembler {

  private static final Logger LOG = Logger.getLogger(RoutingPromptAssembler.class);

  private final List<RoutingPromptSection> sections;

  @Inject
  public RoutingPromptAssembler(Instance<RoutingPromptSection> sections) {
    this.sections =
        sections.stream()
            .sorted(Comparator.comparingInt(RoutingPromptAssembler::priority))
            .toList();
  }

  public RoutingPromptAssembler(List<RoutingPromptSection> sections) {
    this.sections =
        sections.stream()
            .sorted(Comparator.comparingInt(RoutingPromptAssembler::priority))
            .toList();
  }

  /**
   * Assemble all prompt sections for the given routing context.
   *
   * @param context the routing context
   * @param eligible the pre-filtered candidate list
   * @return the assembled prompt string, or {@code null} if no section contributed content
   */
  public @Nullable String assemble(AgentRoutingContext context, List<AgentCandidate> eligible) {
    return assemble(context, eligible, Integer.MAX_VALUE);
  }

  public @Nullable String assemble(
      AgentRoutingContext context, List<AgentCandidate> eligible, int maxBudgetChars) {
    var sb = new StringBuilder();
    for (var section : sections) {
      try {
        String rendered = section.render(context, eligible);
        if (rendered != null && !rendered.isBlank()) {
          if (sb.length() + rendered.length() + 2 > maxBudgetChars) {
            LOG.debugf(
                "Budget exceeded (%d/%d chars) — dropping section: %s",
                sb.length(), maxBudgetChars, section.getClass().getName());
            continue;
          }
          if (!sb.isEmpty()) sb.append("\n\n");
          sb.append(rendered);
        }
      } catch (Exception e) {
        LOG.warnf(e, "RoutingPromptSection threw — skipping: %s", section.getClass().getName());
      }
    }
    return sb.isEmpty() ? null : sb.toString();
  }

  private static int priority(RoutingPromptSection section) {
    var annotation = section.getClass().getAnnotation(jakarta.annotation.Priority.class);
    return annotation != null ? annotation.value() : Integer.MAX_VALUE;
  }
}
