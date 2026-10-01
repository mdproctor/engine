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
package io.casehub.engine.internal.improvement.area;

import io.casehub.api.model.stigmergy.CapabilityAreaAssessment;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.UUID;

@ApplicationScoped
public class CognitiveMemoryCapabilityArea extends AbstractCapabilityArea {

  @Override
  public String id() {
    return "cognitive-memory";
  }

  @Override
  public String name() {
    return "Cognitive Memory";
  }

  @Override
  public String description() {
    return "Knowledge storage and retrieval — placeholder until cognitive layer provides assessment";
  }

  @Override
  public CapabilityAreaAssessment assess(UUID caseId, String tenancyId) {
    return neutralAssessment();
  }
}
