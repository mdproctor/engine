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
 * Event fired when a key in the working layer of a {@link CaseContext} is changed via the flat API
 * ({@code set()}, {@code setAll()}, {@code remove()}, etc.).
 *
 * <p>{@code oldValue} is captured atomically with the write — no TOCTOU race. {@code oldValue} is
 * {@code null} when a key is created; {@code newValue} is {@code null} when a key is removed.
 *
 * @param key the context key that changed
 * @param oldValue the previous value (may be {@code null})
 * @param newValue the new value (may be {@code null})
 */
public record ContextChangeEvent(String key, Object oldValue, Object newValue) {}
