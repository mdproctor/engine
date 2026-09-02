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
package io.casehub.codegen;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import io.casehub.codegen.record.MappingParser;
import io.casehub.codegen.record.RecordEmitter;
import io.casehub.codegen.record.RecordMapping;
import io.casehub.codegen.record.SchemaParser;
import io.casehub.codegen.record.SchemaType;
import io.casehub.codegen.record.TypeMapping;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

public class CasehubRecordCodegen {

  public static void main(String[] args) throws IOException {
    if (args.length < 4) {
      System.err.println(
          "Usage: CasehubRecordCodegen <schemaFile> <mappingFile> <outputDir> <targetPackage>");
      System.exit(1);
    }

    File schemaFile = new File(args[0]);
    File mappingFile = new File(args[1]);
    File outputDir = new File(args[2]);
    String targetPackage = args[3];

    ObjectMapper yaml = new ObjectMapper(new YAMLFactory());
    JsonNode schemaRoot = yaml.readTree(schemaFile);
    JsonNode mappingRoot = yaml.readTree(mappingFile);

    Map<String, SchemaType> schemaTypes = SchemaParser.parse(schemaRoot);
    RecordMapping mapping = MappingParser.parse(mappingRoot);

    Path packageDir = outputDir.toPath().resolve(targetPackage.replace('.', '/'));
    Files.createDirectories(packageDir);

    int generated = 0;
    for (Map.Entry<String, TypeMapping> entry : mapping.types().entrySet()) {
      String schemaTypeName = entry.getKey();
      TypeMapping typeMapping = entry.getValue();

      SchemaType schemaType = schemaTypes.get(schemaTypeName);
      if (schemaType == null) {
        schemaType = new SchemaType(schemaTypeName, List.of());
      }

      String source = RecordEmitter.emit(schemaType, typeMapping, mapping);
      Path outputFile = packageDir.resolve(typeMapping.recordName() + ".java");
      Files.writeString(outputFile, source);
      generated++;
    }

    System.out.printf("Generated %d record(s) to %s%n", generated, packageDir);
  }
}
