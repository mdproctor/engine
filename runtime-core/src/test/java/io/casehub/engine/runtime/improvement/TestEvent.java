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
package io.casehub.engine.internal.improvement;

import jakarta.enterprise.event.Event;
import jakarta.enterprise.event.NotificationOptions;
import jakarta.enterprise.util.TypeLiteral;
import java.lang.annotation.Annotation;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

class TestEvent<T> implements Event<T> {

  private final List<T> fired = new ArrayList<>();

  List<T> fired() {
    return fired;
  }

  void clear() {
    fired.clear();
  }

  @Override
  public void fire(T event) {
    fired.add(event);
  }

  @Override
  @SuppressWarnings("unchecked")
  public <U extends T> CompletionStage<U> fireAsync(U event) {
    fired.add(event);
    return CompletableFuture.completedFuture(event);
  }

  @Override
  @SuppressWarnings("unchecked")
  public <U extends T> CompletionStage<U> fireAsync(U event, NotificationOptions options) {
    fired.add(event);
    return CompletableFuture.completedFuture(event);
  }

  @Override
  public Event<T> select(Annotation... qualifiers) {
    throw new UnsupportedOperationException();
  }

  @Override
  @SuppressWarnings("unchecked")
  public <U extends T> Event<U> select(Class<U> subtype, Annotation... qualifiers) {
    throw new UnsupportedOperationException();
  }

  @Override
  @SuppressWarnings("unchecked")
  public <U extends T> Event<U> select(TypeLiteral<U> subtype, Annotation... qualifiers) {
    throw new UnsupportedOperationException();
  }
}
