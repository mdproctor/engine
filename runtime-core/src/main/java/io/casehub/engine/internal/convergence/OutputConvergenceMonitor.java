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
package io.casehub.engine.internal.convergence;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.casehub.api.model.convergence.OutputConvergenceConfig;
import io.casehub.engine.common.spi.Resettable;
import jakarta.annotation.Nullable;
import jakarta.enterprise.context.ApplicationScoped;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@ApplicationScoped
public class OutputConvergenceMonitor implements Resettable {

  private static final ObjectMapper MAPPER = new ObjectMapper();
  private final ConcurrentHashMap<UUID, ConcurrentHashMap<String, List<OutputFingerprint>>> state =
      new ConcurrentHashMap<>();

  public void recordOutput(
      UUID caseId, String bindingName, String executorName, Map<String, Object> output) {
    var perCase = state.computeIfAbsent(caseId, k -> new ConcurrentHashMap<>());
    var window = perCase.computeIfAbsent(bindingName, k -> new ArrayList<>());
    synchronized (window) {
      window.add(
          new OutputFingerprint(executorName, Set.copyOf(output.keySet()), hashValues(output)));
    }
  }

  public Optional<ConvergenceResult> detectConvergence(
      UUID caseId, String bindingName, @Nullable OutputConvergenceConfig config) {
    if (config == null) return Optional.empty();
    var perCase = state.get(caseId);
    if (perCase == null) return Optional.empty();
    var window = perCase.get(bindingName);
    if (window == null) return Optional.empty();

    List<OutputFingerprint> snapshot;
    synchronized (window) {
      while (window.size() > config.outputWindowSize()) window.remove(0);
      snapshot = List.copyOf(window);
    }

    if (snapshot.size() < config.convergenceMinSamples()) return Optional.empty();

    double totalJaccard = 0;
    int pairs = 0;
    int matchingValueCount = 0;
    Set<String> agents = new LinkedHashSet<>();

    for (int i = 0; i < snapshot.size(); i++) {
      agents.add(snapshot.get(i).executorName);
      for (int j = i + 1; j < snapshot.size(); j++) {
        var a = snapshot.get(i);
        var b = snapshot.get(j);
        double jaccard = jaccard(a.keySet, b.keySet);
        totalJaccard += jaccard;
        pairs++;
        if (jaccard >= config.convergenceThreshold() && a.valueHashes.equals(b.valueHashes)) {
          matchingValueCount++;
        }
      }
    }

    double avgJaccard = pairs > 0 ? totalJaccard / pairs : 0;
    if (avgJaccard >= config.convergenceThreshold() && matchingValueCount > 0) {
      return Optional.of(
          new ConvergenceResult(
              avgJaccard, matchingValueCount, snapshot.size(), List.copyOf(agents)));
    }
    return Optional.empty();
  }

  public void evictByCase(UUID caseId) {
    state.remove(caseId);
  }

  @Override
  public void reset() {
    state.clear();
  }

  private static double jaccard(Set<String> a, Set<String> b) {
    if (a.isEmpty() && b.isEmpty()) return 1.0;
    Set<String> union = new HashSet<>(a);
    union.addAll(b);
    Set<String> intersection = new HashSet<>(a);
    intersection.retainAll(b);
    return (double) intersection.size() / union.size();
  }

  private static Map<String, String> hashValues(Map<String, Object> output) {
    Map<String, String> hashes = new TreeMap<>();
    for (var entry : output.entrySet()) {
      try {
        String json = MAPPER.writeValueAsString(entry.getValue());
        var digest = MessageDigest.getInstance("SHA-256");
        byte[] hash = digest.digest(json.getBytes(StandardCharsets.UTF_8));
        hashes.put(entry.getKey(), Base64.getEncoder().encodeToString(hash));
      } catch (Exception e) {
        hashes.put(entry.getKey(), String.valueOf(entry.getValue().hashCode()));
      }
    }
    return hashes;
  }

  record OutputFingerprint(
      String executorName, Set<String> keySet, Map<String, String> valueHashes) {}

  public record ConvergenceResult(
      double averageJaccard,
      int matchingOutputCount,
      int totalSamples,
      List<String> affectedAgents) {}
}
