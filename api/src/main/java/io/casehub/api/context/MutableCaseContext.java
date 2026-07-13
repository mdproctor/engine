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
 * Engine-internal extension of {@link CaseContext} that exposes writable layer access and layer
 * lifecycle management.
 *
 * <p>Consumer code works with {@link CaseContext} (read/write via the flat API). Engine handlers,
 * the reactor, and episodic layer management work with {@code MutableCaseContext} to access named
 * writable layers and freeze layers after setup.
 */
public interface MutableCaseContext extends CaseContext {

  /**
   * Returns the writable layer with the given name. Creates the layer on demand if it does not
   * exist.
   */
  WritableLayer writableLayer(String name);

  /** Freezes the named layer, making it read-only. Subsequent writes throw. */
  void freezeLayer(String name);

  /** Releases resources held by this context's stores. Default no-op. */
  default void close() {}
}
