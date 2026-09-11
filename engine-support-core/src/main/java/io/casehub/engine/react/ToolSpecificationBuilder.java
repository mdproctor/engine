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
package io.casehub.engine.react;

import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import dev.langchain4j.model.chat.request.json.JsonStringSchema;
import io.casehub.worker.api.Capability;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

final class ToolSpecificationBuilder {

  private static final Pattern FIELD_PATTERN = Pattern.compile("\\.(\\w+)");

  private ToolSpecificationBuilder() {}

  static List<ToolSpecification> buildAll(List<ToolSource> tools) {
    return tools.stream().map(ToolSpecificationBuilder::toSpec).toList();
  }

  static Map<String, ToolSource> buildToolMap(List<ToolSource> tools) {
    return tools.stream()
        .collect(Collectors.toMap(ToolSource::name, t -> t, (a, b) -> a, LinkedHashMap::new));
  }

  private static ToolSpecification toSpec(ToolSource source) {
    return switch (source) {
      case ToolSource.WorkerTool wt ->
          ToolSpecification.builder()
              .name(wt.capability().name())
              .description(wt.capability().description())
              .parameters(deriveParametersFromCapability(wt.capability()))
              .build();
      case ToolSource.LocalTool lt ->
          ToolSpecification.builder()
              .name(lt.name())
              .description(lt.description())
              .parameters(toJsonObjectSchema(lt.parameterSchema()))
              .build();
    };
  }

  private static JsonObjectSchema deriveParametersFromCapability(Capability capability) {
    var inputSchema = capability.inputProjection();
    if (inputSchema == null || inputSchema.equals(".")) {
      return JsonObjectSchema.builder().build();
    }
    var builder = JsonObjectSchema.builder();
    for (var field : extractFieldNames(inputSchema)) {
      builder.addStringProperty(field);
    }
    return builder.build();
  }

  static List<String> extractFieldNames(String jqExpression) {
    var fields = new ArrayList<String>();
    var matcher = FIELD_PATTERN.matcher(jqExpression);
    while (matcher.find()) {
      fields.add(matcher.group(1));
    }
    return fields;
  }

  private static JsonObjectSchema toJsonObjectSchema(Map<String, Object> schema) {
    if (schema == null || schema.isEmpty()) {
      return JsonObjectSchema.builder().build();
    }
    var builder = JsonObjectSchema.builder();
    for (var entry : schema.entrySet()) {
      builder.addProperty(entry.getKey(), JsonStringSchema.builder().build());
    }
    return builder.build();
  }
}
