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
package io.casehub.engine.planning.plan;

public sealed interface CompletionSemantics
    permits CompletionSemantics.All, CompletionSemantics.MOfN, CompletionSemantics.FirstWins {

  record All() implements CompletionSemantics {}

  record MOfN(int m) implements CompletionSemantics {
    public MOfN {
      if (m < 1) throw new IllegalArgumentException("m must be >= 1, was " + m);
    }
  }

  record FirstWins() implements CompletionSemantics {}

  static CompletionSemantics all() {
    return new All();
  }

  static CompletionSemantics mOfN(int m) {
    return new MOfN(m);
  }

  static CompletionSemantics firstWins() {
    return new FirstWins();
  }
}
