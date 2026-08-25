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
package io.casehub.generator;

import io.casehub.api.model.CaseDefinition;
import java.nio.file.Path;

public final class GenerateSchema {

  private GenerateSchema() {}

  public static void main(String[] args) throws Exception {
    var generator = new CaseHubSchemaGenerator();

    Path outputDir = args.length > 0 ? Path.of(args[0]) : Path.of("target/generated-schema");
    Path outputFile = outputDir.resolve("CaseDefinition.yaml");
    generator.generateToYaml(CaseDefinition.class, outputFile);
    System.out.println("Generated schema: " + outputFile.toAbsolutePath());

    Path schemaSource = Path.of("../schema/src/main/resources/schema/CaseDefinition.yaml");
    if (java.nio.file.Files.exists(schemaSource.getParent())) {
      generator.generateToYaml(CaseDefinition.class, schemaSource);
      System.out.println("Updated schema source: " + schemaSource.toAbsolutePath());
    }
  }
}
