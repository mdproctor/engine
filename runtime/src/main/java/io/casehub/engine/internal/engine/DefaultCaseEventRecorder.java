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
package io.casehub.engine.internal.engine;

import io.casehub.api.spi.CaseEventRecorder;
import io.casehub.api.spi.CaseEventRequest;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * Blocking {@link CaseEventRecorder}. Delegates to {@link DefaultReactiveCaseEventRecorder} and
 * awaits.
 */
@ApplicationScoped
public class DefaultCaseEventRecorder implements CaseEventRecorder {

  private final DefaultReactiveCaseEventRecorder delegate;

  @Inject
  public DefaultCaseEventRecorder(DefaultReactiveCaseEventRecorder delegate) {
    this.delegate = delegate;
  }

  @Override
  public void record(CaseEventRequest request) {
    delegate.record(request).await().indefinitely();
  }

  @Override
  public Long recordAndReturnId(CaseEventRequest request) {
    return delegate.recordAndReturnId(request).await().indefinitely();
  }
}
