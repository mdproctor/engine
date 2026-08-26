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

import io.casehub.api.model.Evidence;
import io.casehub.api.model.EvidenceRequirement;
import io.casehub.api.model.JudgmentResponse;
import io.casehub.api.spi.JudgmentVerifier;
import io.casehub.api.spi.VerificationContext;
import io.casehub.api.spi.VerificationResult;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@ApplicationScoped
public class EvidencePresenceVerifier implements JudgmentVerifier {

  @Override
  public String id() {
    return "evidence-presence";
  }

  @Override
  public VerificationResult verify(JudgmentResponse response, VerificationContext context) {
    List<EvidenceRequirement> requirements = context.target().evidenceRequirements();
    if (requirements == null || requirements.isEmpty()) {
      return new VerificationResult.Accepted();
    }

    Set<String> providedNames =
        response.evidence() != null
            ? response.evidence().stream().map(Evidence::name).collect(Collectors.toSet())
            : Set.of();

    List<String> missing = new ArrayList<>();
    for (EvidenceRequirement req : requirements) {
      if (req.required() && !providedNames.contains(req.name())) {
        missing.add(req.name());
      }
    }

    if (missing.isEmpty()) {
      return new VerificationResult.Accepted();
    }

    return new VerificationResult.InsufficientEvidence(
        "Missing required evidence: " + String.join(", ", missing), missing);
  }
}
