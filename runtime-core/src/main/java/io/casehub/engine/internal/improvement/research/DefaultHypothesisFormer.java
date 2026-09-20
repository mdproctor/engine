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
package io.casehub.engine.internal.improvement.research;

import io.casehub.api.model.stigmergy.CapabilityAreaAssessment;
import io.casehub.api.model.stigmergy.ImprovementHypothesis;
import io.casehub.api.model.stigmergy.ResearchAnalysis;
import io.casehub.api.spi.improvement.HypothesisFormer;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.List;

@ApplicationScoped
public class DefaultHypothesisFormer implements HypothesisFormer {

  @Override
  public List<ImprovementHypothesis> form(
      ResearchAnalysis analysis, CapabilityAreaAssessment area) {
    return analysis.findings().stream()
        .map(
            f ->
                new ImprovementHypothesis(
                    f.technique(),
                    area.areaId(),
                    "Improve " + area.areaId() + " via " + f.technique(),
                    f.claimedBenefits(),
                    f.limitations(),
                    area.areaId(),
                    ImprovementHypothesis.RadarRecommendation.ASSESS))
        .toList();
  }
}
