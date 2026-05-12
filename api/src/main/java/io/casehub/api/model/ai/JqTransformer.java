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
package io.casehub.api.model.ai;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;
import net.thisptr.jackson.jq.BuiltinFunctionLoader;
import net.thisptr.jackson.jq.JsonQuery;
import net.thisptr.jackson.jq.Scope;
import net.thisptr.jackson.jq.Versions;
import net.thisptr.jackson.jq.exception.JsonQueryException;

public final class JqTransformer {

  private final JsonQuery query;

  public JqTransformer(String jqExpression) {
    try {
      Scope initScope = Scope.newEmptyScope();
      BuiltinFunctionLoader.getInstance().loadFunctions(Versions.JQ_1_6, initScope);
      this.query = JsonQuery.compile(jqExpression, Versions.JQ_1_6);
    } catch (JsonQueryException e) {
      throw new IllegalArgumentException("Invalid jq expression: " + jqExpression, e);
    }
  }

  public JsonNode apply(JsonNode input) {
    List<JsonNode> results = new ArrayList<>();
    try {
      Scope callScope = Scope.newEmptyScope();
      BuiltinFunctionLoader.getInstance().loadFunctions(Versions.JQ_1_6, callScope);
      query.apply(callScope, input, results::add);
    } catch (JsonQueryException e) {
      throw new AgentException("jq transformation failed", e);
    }
    if (results.isEmpty()) {
      throw new AgentException("jq expression produced no output");
    }
    return results.get(0);
  }
}
