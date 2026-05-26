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

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Request to schedule a job for execution.
 *
 * <p>Contains all information needed to schedule a job: identifier, schedule strategy, job class,
 * and data to pass to the job when it executes.
 */
public class ScheduledJobRequest {

  private final JobIdentifier jobId;
  private final ScheduleStrategy schedule;
  private final Class<?> jobClass;
  private final Map<String, Object> data;

  private ScheduledJobRequest(
      JobIdentifier jobId, ScheduleStrategy schedule, Class<?> jobClass, Map<String, Object> data) {
    this.jobId = Objects.requireNonNull(jobId, "jobId must not be null");
    this.schedule = Objects.requireNonNull(schedule, "schedule must not be null");
    this.jobClass = jobClass; // may be null - scheduler will determine based on data
    this.data = Map.copyOf(data); // defensive copy
  }

  public static Builder builder() {
    return new Builder();
  }

  public JobIdentifier getJobId() {
    return jobId;
  }

  public ScheduleStrategy getSchedule() {
    return schedule;
  }

  public Class<?> getJobClass() {
    return jobClass;
  }

  public Map<String, Object> getData() {
    return data;
  }

  @Override
  public String toString() {
    return "ScheduledJobRequest{"
        + "jobId="
        + jobId
        + ", schedule="
        + schedule
        + ", jobClass="
        + (jobClass != null ? jobClass.getSimpleName() : "null")
        + '}';
  }

  public static class Builder {
    private JobIdentifier jobId;
    private ScheduleStrategy schedule;
    private Class<?> jobClass;
    private Map<String, Object> data = new HashMap<>();

    public Builder jobId(JobIdentifier jobId) {
      this.jobId = jobId;
      return this;
    }

    public Builder schedule(ScheduleStrategy schedule) {
      this.schedule = schedule;
      return this;
    }

    public Builder jobClass(Class<?> jobClass) {
      this.jobClass = jobClass;
      return this;
    }

    public Builder data(Map<String, Object> data) {
      this.data = new HashMap<>(data);
      return this;
    }

    public Builder addData(String key, Object value) {
      this.data.put(key, value);
      return this;
    }

    public ScheduledJobRequest build() {
      return new ScheduledJobRequest(jobId, schedule, jobClass, data);
    }
  }
}
