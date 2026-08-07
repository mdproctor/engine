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
package io.casehub.engine.plan.snapshot;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "kind")
@JsonSubTypes({
  @JsonSubTypes.Type(value = CompletionSemanticsSnapshot.AllSnapshot.class, name = "All"),
  @JsonSubTypes.Type(value = CompletionSemanticsSnapshot.MOfNSnapshot.class, name = "MOfN"),
  @JsonSubTypes.Type(
      value = CompletionSemanticsSnapshot.FirstWinsSnapshot.class,
      name = "FirstWins")
})
public sealed interface CompletionSemanticsSnapshot {
  record AllSnapshot() implements CompletionSemanticsSnapshot {}

  record MOfNSnapshot(int m) implements CompletionSemanticsSnapshot {}

  record FirstWinsSnapshot() implements CompletionSemanticsSnapshot {}
}
