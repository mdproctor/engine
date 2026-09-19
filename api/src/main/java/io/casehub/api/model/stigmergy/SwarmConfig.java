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

public record SwarmConfig(
    @Nullable Integer maxSwarmSize,
    @Nullable Double roleSimilarityThreshold,
    @Nullable Integer roleMinClusterSize,
    @Nullable Integer roleDetectionWindow,
    @Nullable Integer detectionInterval,
    @Nullable RoleDomainWeights domainWeights,
    @Nullable Double teamAffinityThreshold,
    @Nullable Integer teamMinSize,
    @Nullable Double progressChangeThreshold,
    @Nullable ProvisionBudget provisionBudget,
    @Nullable IntegrationPolicy integrationPolicy) {

  public SwarmConfig(
      @Nullable Integer maxSwarmSize,
      @Nullable Double roleSimilarityThreshold,
      @Nullable Integer roleMinClusterSize,
      @Nullable Integer roleDetectionWindow,
      @Nullable Integer detectionInterval,
      @Nullable RoleDomainWeights domainWeights,
      @Nullable Double teamAffinityThreshold,
      @Nullable Integer teamMinSize,
      @Nullable Double progressChangeThreshold) {
    this(
        maxSwarmSize,
        roleSimilarityThreshold,
        roleMinClusterSize,
        roleDetectionWindow,
        detectionInterval,
        domainWeights,
        teamAffinityThreshold,
        teamMinSize,
        progressChangeThreshold,
        null,
        null);
  }

  public int effectiveMaxSwarmSize() {
    return maxSwarmSize != null ? maxSwarmSize : 20;
  }

  public double effectiveRoleSimilarityThreshold() {
    return roleSimilarityThreshold != null ? roleSimilarityThreshold : 0.7;
  }

  public int effectiveRoleMinClusterSize() {
    return roleMinClusterSize != null ? roleMinClusterSize : 2;
  }

  public int effectiveRoleDetectionWindow() {
    return roleDetectionWindow != null ? roleDetectionWindow : 20;
  }

  public int effectiveDetectionInterval() {
    return detectionInterval != null ? detectionInterval : 10;
  }

  public RoleDomainWeights effectiveDomainWeights() {
    return domainWeights != null ? domainWeights : RoleDomainWeights.EQUAL;
  }

  public double effectiveTeamAffinityThreshold() {
    return teamAffinityThreshold != null ? teamAffinityThreshold : 0.5;
  }

  public int effectiveTeamMinSize() {
    return teamMinSize != null ? teamMinSize : 2;
  }

  public double effectiveProgressChangeThreshold() {
    return progressChangeThreshold != null ? progressChangeThreshold : 0.1;
  }
}
