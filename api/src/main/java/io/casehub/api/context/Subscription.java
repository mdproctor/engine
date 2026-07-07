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
package io.casehub.api.context;

/**
 * Handle returned by {@link CaseContext#onChange} and {@link CaseContext#onAnyChange} to allow
 * callers to unsubscribe from change notifications.
 *
 * <p><b>Callers must call {@link #cancel()} when the listener is no longer needed.</b> Listeners
 * are held by strong reference in the CaseContext and will accumulate if not cancelled, causing
 * unbounded memory growth in long-running cases.
 */
@FunctionalInterface
public interface Subscription {

  /** A no-op subscription whose {@link #cancel()} method does nothing. */
  Subscription NOOP = () -> {};

  /** Removes this listener from the context. Subsequent changes will no longer be notified. */
  void cancel();
}
