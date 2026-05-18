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
package io.casehub.api.model;

import java.util.HashSet;
import java.util.Objects;
import java.util.Set;

/**
 * External dependencies declaration (secrets and config maps).
 *
 * <p>Inspired by CNCF Serverless Workflow 'use' section.
 */
public class Use {

  private Set<String> secrets;
  private Set<String> configMaps;

  public Use() {
    this.secrets = new HashSet<>();
    this.configMaps = new HashSet<>();
  }

  public Set<String> getSecrets() {
    return secrets;
  }

  public void setSecrets(Set<String> secrets) {
    this.secrets = secrets;
  }

  public Set<String> getConfigMaps() {
    return configMaps;
  }

  public void setConfigMaps(Set<String> configMaps) {
    this.configMaps = configMaps;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (o == null || getClass() != o.getClass()) return false;
    Use use = (Use) o;
    return Objects.equals(secrets, use.secrets) && Objects.equals(configMaps, use.configMaps);
  }

  @Override
  public int hashCode() {
    return Objects.hash(secrets, configMaps);
  }

  @Override
  public String toString() {
    return "Use{" + "secrets=" + secrets + ", configMaps=" + configMaps + '}';
  }
}
