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
package io.casehub.api.spi;

import static java.lang.annotation.ElementType.FIELD;
import static java.lang.annotation.ElementType.METHOD;
import static java.lang.annotation.ElementType.PARAMETER;
import static java.lang.annotation.ElementType.TYPE;
import static java.lang.annotation.RetentionPolicy.RUNTIME;

import jakarta.inject.Qualifier;
import java.lang.annotation.Retention;
import java.lang.annotation.Target;

/**
 * CDI qualifier for {@link ActionRiskClassifier} implementations.
 *
 * <p>Consumer implementations must be annotated {@code @RiskClassifier @ApplicationScoped} so the
 * engine's {@code ChainedActionRiskClassifier} can discover and chain them without circular
 * dependency. The chain implements {@link ActionRiskClassifier}, not {@code ActionRiskClassifier},
 * which prevents self-injection.
 *
 * <p>Multiple classifiers from different repos (casehub-aml, casehub-clinical) are automatically
 * chained — the most restrictive {@link RiskDecision.GateRequired} wins.
 */
@Qualifier
@Retention(RUNTIME)
@Target({METHOD, FIELD, PARAMETER, TYPE})
public @interface RiskClassifier {}
