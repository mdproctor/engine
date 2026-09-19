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
package io.casehub.api.engine;

import io.casehub.api.model.stigmergy.BehavioralFingerprint;
import io.casehub.api.model.stigmergy.DetectedRole;
import io.casehub.api.model.stigmergy.DetectedTeam;
import io.casehub.api.model.stigmergy.SwarmProgress;
import java.util.List;
import java.util.Map;

public interface MetricsSpace {

  Map<String, Double> activityRates();

  Map<String, Long> budgetUsage();

  BehavioralFingerprint myFingerprint();

  SwarmProgress swarmProgress();

  List<DetectedRole> detectedRoles();

  List<DetectedTeam> detectedTeams();

  MetricsSpace NOOP =
      new MetricsSpace() {
        @Override
        public Map<String, Double> activityRates() {
          return Map.of();
        }

        @Override
        public Map<String, Long> budgetUsage() {
          return Map.of();
        }

        @Override
        public BehavioralFingerprint myFingerprint() {
          return BehavioralFingerprint.EMPTY;
        }

        @Override
        public SwarmProgress swarmProgress() {
          return SwarmProgress.EMPTY;
        }

        @Override
        public List<DetectedRole> detectedRoles() {
          return List.of();
        }

        @Override
        public List<DetectedTeam> detectedTeams() {
          return List.of();
        }
      };
}
