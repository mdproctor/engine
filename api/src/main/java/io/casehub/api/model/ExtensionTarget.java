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
package io.casehub.api.model;

/**
 * Runtime plugin escape hatch for binding targets not yet in the sealed hierarchy.
 *
 * <p>No dispatcher exists in the engine for {@code ExtensionTarget} — unknown extension targets are
 * logged as warnings. Implementations must be registered explicitly with the engine runtime.
 */
public non-sealed interface ExtensionTarget extends BindingTarget {}
