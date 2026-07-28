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
package io.casehub.engine.common.internal.event;

import io.casehub.api.model.SubCase;
import io.casehub.engine.common.internal.model.CaseInstance;

/**
 * Published by {@link io.casehub.engine.internal.engine.handler.CaseContextChangedEventHandler}
 * when a binding with a SubCase definition fires. Carries the evaluated child initial context
 * (result of SubCase.inputMapping applied to the parent CaseContext) and the binding name so {@link
 * io.casehub.engine.planning.subcase.SubCaseExecutionHandler} can locate the correct PlanItem.
 *
 * @param bindingName the name of the Binding in the parent CaseDefinition that fired
 */
public record SubCaseScheduleEvent(
    CaseInstance parentInstance,
    SubCase subCase,
    Object childInitialContext,
    String contextBridgeType,
    String bindingName) {}
