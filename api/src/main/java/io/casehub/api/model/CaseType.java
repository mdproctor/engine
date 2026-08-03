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

import static java.lang.annotation.ElementType.FIELD;
import static java.lang.annotation.ElementType.METHOD;
import static java.lang.annotation.ElementType.PARAMETER;
import static java.lang.annotation.ElementType.TYPE;
import static java.lang.annotation.RetentionPolicy.RUNTIME;

import jakarta.enterprise.util.Nonbinding;
import jakarta.inject.Qualifier;
import java.lang.annotation.Retention;
import java.lang.annotation.Target;

/**
 * CDI qualifier for case-type-scoped injection.
 *
 * <p>Enables case definitions to declare type-specific dependencies that are resolved at case start
 * time. The {@code value()} matches {@link CaseDefinition#types()} paths.
 *
 * <p>Example:
 *
 * <pre>{@code
 * @ApplicationScoped
 * @CaseType("clinical/screening")
 * public class ScreeningOrchestrator implements CaseOutcomeObserver {
 *     // ...
 * }
 * }</pre>
 *
 * <p>The {@code value()} is {@link Nonbinding} — CDI does not use it for bean selection. Runtime
 * resolution uses {@link CaseDefinition#types()} to filter discovered beans.
 */
@Qualifier
@Retention(RUNTIME)
@Target({TYPE, METHOD, FIELD, PARAMETER})
public @interface CaseType {
  @Nonbinding
  String value();
}
