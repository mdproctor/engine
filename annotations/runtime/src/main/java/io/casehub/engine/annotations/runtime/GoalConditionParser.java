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
package io.casehub.engine.annotations.runtime;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class GoalConditionParser {

  private static final Pattern KEY_PATTERN = Pattern.compile("\\.(\\w+)(?:\\.\\w+)*\\s*(!?=)");

  private GoalConditionParser() {}

  public static Set<String> parseEffectKeys(String condition) {
    if (condition == null || condition.isBlank()) return Set.of();
    Set<String> keys = new LinkedHashSet<>();
    Matcher matcher = KEY_PATTERN.matcher(condition);
    while (matcher.find()) {
      keys.add(matcher.group(1));
    }
    return Set.copyOf(keys);
  }
}
