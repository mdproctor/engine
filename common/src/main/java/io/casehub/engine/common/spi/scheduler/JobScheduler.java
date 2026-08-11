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
package io.casehub.engine.common.spi.scheduler;

import io.casehub.engine.common.internal.scheduler.JobIdentifier;
import io.casehub.engine.common.internal.scheduler.ScheduledJobRequest;

/**
 * Scheduler abstraction for managing delayed and recurring job execution.
 *
 * <p>Implementations may use Quartz, Vert.x timers, or other scheduling backends. This SPI isolates
 * the engine from scheduler-specific APIs.
 *
 * <p><b>Implementations must:</b>
 *
 * <ul>
 *   <li>Support job persistence or in-memory scheduling based on configuration
 *   <li>Handle job cancellation idempotently (cancelling non-existent job returns false)
 *   <li>Execute jobs via the provided job class (must implement the scheduler's Job interface)
 * </ul>
 */
public interface JobScheduler {

  /**
   * Schedule a job for delayed or recurring execution.
   *
   * <p>If a job with the same {@link JobIdentifier} already exists, behavior is
   * implementation-defined (may throw exception or replace existing job).
   *
   * @param request job configuration including schedule, data, and job class
   * @throws RuntimeException if scheduling fails
   */
  void schedule(ScheduledJobRequest request);

  void schedule(ScheduledJobRequest.Builder builder);

  /**
   * Cancel a specific scheduled job.
   *
   * @param jobId unique job identifier
   * @return true if job was cancelled, false if job did not exist
   */
  boolean cancel(JobIdentifier jobId);

  /**
   * Cancel all jobs in a group.
   *
   * @param groupName job group name
   * @return count of cancelled jobs
   */
  int cancelGroup(String groupName);

  /**
   * Check if a job exists in the scheduler.
   *
   * @param jobId unique job identifier
   * @return true if job exists, false otherwise
   */
  boolean exists(JobIdentifier jobId);
}
