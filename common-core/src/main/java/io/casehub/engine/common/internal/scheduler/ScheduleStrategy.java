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
 * Execution schedule for a job.
 *
 * <p>Sealed interface with three strategies:
 *
 * <ul>
 *   <li>{@link CronSchedule} - cron-based recurring execution
 *   <li>{@link DelaySchedule} - one-shot execution after delay
 *   <li>{@link FixedAtSchedule} - one-shot execution at specific time
 * </ul>
 */
public sealed interface ScheduleStrategy
    permits ScheduleStrategy.CronSchedule,
        ScheduleStrategy.DelaySchedule,
        ScheduleStrategy.FixedAtSchedule {

  /**
   * Cron-based recurring schedule.
   *
   * @param expression Quartz cron expression (e.g., "0 0 * * * ?" for hourly)
   */
  record CronSchedule(String expression) implements ScheduleStrategy {
    public CronSchedule {
      Objects.requireNonNull(expression, "expression must not be null");
    }
  }

  /**
   * Delay-based one-shot schedule.
   *
   * @param delayMillis milliseconds from now to execute
   */
  record DelaySchedule(long delayMillis) implements ScheduleStrategy {
    public DelaySchedule {
      if (delayMillis < 0) {
        throw new IllegalArgumentException("delayMillis must be non-negative");
      }
    }
  }

  /**
   * Fixed-time one-shot schedule.
   *
   * @param executeAtMillis epoch milliseconds when to execute
   */
  record FixedAtSchedule(long executeAtMillis) implements ScheduleStrategy {
    public FixedAtSchedule {
      if (executeAtMillis < 0) {
        throw new IllegalArgumentException("executeAtMillis must be non-negative");
      }
    }
  }
}
