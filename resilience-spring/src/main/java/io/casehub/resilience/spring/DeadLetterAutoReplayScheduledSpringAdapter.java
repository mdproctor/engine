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
package io.casehub.resilience.spring;

import io.casehub.resilience.deadletter.DeadLetterAutoReplayJob;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class DeadLetterAutoReplayScheduledSpringAdapter {

  private final DeadLetterAutoReplayJob job;

  public DeadLetterAutoReplayScheduledSpringAdapter(DeadLetterAutoReplayJob job) {
    this.job = job;
  }

  @Scheduled(fixedRateString = "${casehub.dlq.auto-replay.interval:1800000}")
  public void scan() {
    job.scan();
  }
}
