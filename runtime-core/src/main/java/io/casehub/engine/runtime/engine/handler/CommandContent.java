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
package io.casehub.engine.internal.engine.handler;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.Map;

/**
 * Typed representation of the COMMAND content posted to a worker channel.
 *
 * <p>Fields: type (always "COMMAND"), capability name, correlationId (event log id), input data
 * extracted from the case context, and an optional deadline (ISO-8601 Instant string, present when
 * the case has a PropagationContext deadline). The deadline field is omitted from serialized JSON
 * when null to preserve existing wire format compatibility.
 *
 * <p>Consumer: Claudony's {@code ClaudonyReactiveCaseChannelProvider} receives {@code
 * correlationId} and {@code deadline} as direct SPI parameters (since engine#343 / claudony#135).
 * The {@code CommandContent} JSON still carries both fields for the worker agent to read.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
record CommandContent(
    String type,
    String capability,
    String correlationId,
    Map<String, Object> input,
    String deadline) {}
