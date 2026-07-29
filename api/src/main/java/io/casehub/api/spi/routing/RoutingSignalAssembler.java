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
import org.jboss.logging.Logger;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Discovers all {@link RoutingSignalProvider} implementations via CDI, sorts them by {@link
 * jakarta.annotation.Priority} (lower values first), and assembles their signals into a map keyed
 * by provider {@link RoutingSignalProvider#id()}.
 *
 * <p>Providers returning {@code null} are skipped. Providers that throw are logged and skipped — a
 * failing provider never prevents other providers from contributing.
 *
 * <p>Out-of-range scores (outside [0.0, 1.0]) are clamped and logged.
 */
@ApplicationScoped
public class RoutingSignalAssembler {

  private static final Logger LOG = Logger.getLogger(RoutingSignalAssembler.class);

  private final List<RoutingSignalProvider> providers;

  @Inject
  public RoutingSignalAssembler(Instance<RoutingSignalProvider> providers) {
    this.providers =
        providers.stream()
            .sorted(Comparator.comparingInt(RoutingSignalAssembler::priority))
            .toList();
  }

  public RoutingSignalAssembler(List<RoutingSignalProvider> providers) {
    this.providers =
        providers.stream()
            .sorted(Comparator.comparingInt(RoutingSignalAssembler::priority))
            .toList();
  }

  public Map<String, RoutingSignal> assemble(
      AgentRoutingContext context, List<AgentCandidate> eligible) {
      var result = new LinkedHashMap<String, RoutingSignal>();
      for (var provider : providers) {
          try {
              RoutingSignal signal = provider.evaluate(context, eligible);
              if (signal != null) {
                  result.put(provider.id(), clampScores(signal, provider.id()));
              }
          } catch (Exception e) {
              LOG.warnf(e, "RoutingSignalProvider threw — skipping: %s", provider.getClass().getName());
          }
      }
      return result;}

  private static RoutingSignal clampScores(RoutingSignal signal, String providerId) {
      var     clamped    = new LinkedHashMap<String, RoutingSignal.CandidateSignal>();
      boolean anyClamped = false;
      for (var entry : signal.candidates().entrySet()) {
          var cs = entry.getValue();
          switch (cs) {
              case RoutingSignal.CandidateSignal.Score s -> {
                  double score = s.value();
                  if (score < 0.0 || score > 1.0) {
                      anyClamped = true;
                      score      = Math.max(0.0, Math.min(1.0, score));
                  }
                  clamped.put(
                          entry.getKey(), new RoutingSignal.CandidateSignal.Score(score, s.rationale()));
              }
              case RoutingSignal.CandidateSignal.Exclude e -> clamped.put(entry.getKey(), e);
              case RoutingSignal.CandidateSignal.Escalate e -> clamped.put(entry.getKey(), e);
          }
      }
      if (anyClamped) {
          LOG.warnf(
                  "RoutingSignalProvider '%s' returned out-of-range scores — clamped to [0,1]", providerId);
          return new RoutingSignal(clamped);
      }
      return signal;}

  private static int priority(RoutingSignalProvider provider) {
    var annotation = provider.getClass().getAnnotation(jakarta.annotation.Priority.class);
    return annotation != null ? annotation.value() : Integer.MAX_VALUE;
  }
}
