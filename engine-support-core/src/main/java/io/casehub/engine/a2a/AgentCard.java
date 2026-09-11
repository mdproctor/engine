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
package io.casehub.engine.a2a;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record AgentCard(
    String name,
    String description,
    String url,
    String version,
    String provider,
    List<Skill> skills) {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  @JsonIgnoreProperties(ignoreUnknown = true)
  public record Skill(String id, String name, String description) {}

  public static AgentCard parse(String json) throws IOException {
    return MAPPER.readValue(json, AgentCard.class);
  }

  public boolean hasSkill(String skillName) {
    if (skills == null) return false;
    return skills.stream().anyMatch(s -> skillName.equals(s.id()) || skillName.equals(s.name()));
  }
}
