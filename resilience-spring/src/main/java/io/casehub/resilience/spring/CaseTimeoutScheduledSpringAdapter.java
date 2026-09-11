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

import io.casehub.resilience.timeout.CaseTimeoutEnforcer;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class CaseTimeoutScheduledSpringAdapter {

  private final CaseTimeoutEnforcer enforcer;

  public CaseTimeoutScheduledSpringAdapter(CaseTimeoutEnforcer enforcer) {
    this.enforcer = enforcer;
  }

  @Scheduled(fixedRateString = "${casehub.resilience.timeout.check-interval:1000}")
  public void scanForTimeouts() {
    enforcer.scanForTimeouts();
  }
}
