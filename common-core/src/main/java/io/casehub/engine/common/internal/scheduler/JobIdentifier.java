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
package io.casehub.engine.common.internal.scheduler;

import java.util.Objects;

/**
 * Unique identifier for a scheduled job.
 *
 * <p>Jobs are identified by a name within a group. This allows logical grouping of related jobs
 * (e.g., all jobs for a specific case instance).
 */
public class JobIdentifier {

  private final String name;
  private final String group;

  private JobIdentifier(String name, String group) {
    this.name = Objects.requireNonNull(name, "name must not be null");
    this.group = Objects.requireNonNull(group, "group must not be null");
  }

  /**
   * Create a job identifier.
   *
   * @param name unique job name within the group
   * @param group job group name
   * @return JobIdentifier instance
   */
  public static JobIdentifier of(String name, String group) {
    return new JobIdentifier(name, group);
  }

  public String getName() {
    return name;
  }

  public String getGroup() {
    return group;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (!(o instanceof JobIdentifier that)) return false;
    return name.equals(that.name) && group.equals(that.group);
  }

  @Override
  public int hashCode() {
    return Objects.hash(name, group);
  }

  @Override
  public String toString() {
    return group + "." + name;
  }
}
