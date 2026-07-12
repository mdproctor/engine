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
package io.casehub.api.model.cbr;

/**
 * CDI marker interface for registering custom CBR case subtypes with {@code CbrRetrievalService}.
 * Implementations are discovered via {@code @Inject @All Instance<CbrCaseTypeRegistration>} and
 * merged into the built-in type map at construction time.
 *
 * <p>A single registration may override a built-in mapping (e.g., "plan" →
 * CustomPlanCbrCase.class), but two registrations claiming the same {@code cbrType()} key throw
 * {@link IllegalStateException} at construction — fail-fast, no silent override.
 */
public interface CbrCaseTypeRegistration {

  /** The CBR type discriminator — matches {@link CbrCase#cbrType()}. */
  String cbrType();

  /** The Java class to use for deserialization when retrieving cases of this type. */
  Class<?> caseClass();
}
