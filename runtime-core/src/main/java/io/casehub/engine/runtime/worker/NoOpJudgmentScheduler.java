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

import io.casehub.engine.common.spi.JudgmentRequest;
import io.casehub.engine.common.spi.JudgmentScheduleRequest;
import io.casehub.engine.common.spi.JudgmentScheduler;
import org.jboss.logging.Logger;

@SuppressWarnings("removal")
public class NoOpJudgmentScheduler implements JudgmentScheduler {

  private static final Logger LOG = Logger.getLogger(NoOpJudgmentScheduler.class);

  @Override
  public void schedule(JudgmentScheduleRequest request) {
    LOG.debugf(
        "NoOpJudgmentScheduler — judgment request not dispatched: caseId=%s binding=%s",
        request.caseId(), request.bindingName());
  }

  @Override
  public void schedule(JudgmentRequest request) {
    LOG.debugf(
        "NoOpJudgmentScheduler — judgment request not dispatched: caseId=%s binding=%s",
        request.caseId(), request.bindingName());
  }
}
