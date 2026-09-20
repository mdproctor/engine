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
package io.casehub.api.model.stigmergy;

import jakarta.annotation.Nullable;
import java.util.List;

public record ResearchMethodology(
    @Nullable Integer horizonScanIntervalDays,
    @Nullable Integer areaRefreshStalenessThresholdDays,
    @Nullable Integer maxSearchResultsPerTier,
    @Nullable List<String> searchChannels,
    @Nullable Integer minTriangulationSources,
    @Nullable Integer strategyDwellTimeDays) {

  public int effectiveHorizonScanIntervalDays() {
    return horizonScanIntervalDays != null ? horizonScanIntervalDays : 90;
  }

  public int effectiveAreaRefreshStalenessThresholdDays() {
    return areaRefreshStalenessThresholdDays != null ? areaRefreshStalenessThresholdDays : 30;
  }

  public int effectiveMinTriangulationSources() {
    return minTriangulationSources != null ? minTriangulationSources : 3;
  }

  public int effectiveStrategyDwellTimeDays() {
    return strategyDwellTimeDays != null ? strategyDwellTimeDays : 14;
  }
}
