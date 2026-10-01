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
package io.casehub.engine.internal.engine;

import static org.assertj.core.api.Assertions.assertThat;

import io.casehub.api.model.CaseDefinition;
import io.casehub.api.model.cbr.CbrConfig;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

@QuarkusTest
class CbrConfigRegistrationValidationTest {

  @Inject DefaultCaseDefinitionRegistry registry;

  private final List<LogRecord> logRecords = new ArrayList<>();
  private Handler testHandler;
  private Logger logger;

  @BeforeEach
  void setupLogCapture() {
    logger = Logger.getLogger(DefaultCaseDefinitionRegistry.class.getName());
    logger.setLevel(Level.WARNING);
    testHandler =
        new Handler() {
          @Override
          public void publish(LogRecord record) {
            logRecords.add(record);
          }

          @Override
          public void flush() {}

          @Override
          public void close() {}
        };
    logger.addHandler(testHandler);
  }

  @AfterEach
  void teardownLogCapture() {
    if (testHandler != null && logger != null) {
      logger.removeHandler(testHandler);
    }
    logRecords.clear();
  }

  @Test
  void registerCaseDefinition_cbrConfigWithNoDomainAndNoEpisodicMemory_logsWarning() {
    // CbrConfig present, domain=null, no EpisodicMemoryConfig
    var cbrConfig =
        CbrConfig.builder()
            .feature("amount", ".transaction.amount")
            .topK(5)
            .minSimilarity(0.7)
            .build();

    var definition =
        CaseDefinition.builder()
            .namespace("test")
            .name("cbr-no-domain")
            .version("1.0")
            .cbrConfig(cbrConfig)
            .build();

    logRecords.clear(); // Clear any startup logs

    registry.registerCaseDefinition(definition);

    String formattedMessage =
        logRecords.isEmpty()
            ? ""
            : String.format(logRecords.get(0).getMessage(), logRecords.get(0).getParameters());

    assertThat(logRecords).isNotEmpty();
    assertThat(formattedMessage).contains("CbrConfig").contains("domain");
  }

  @Test
  void registerCaseDefinition_cbrConfigWithInvalidJqFeatureExtractor_logsWarning() {
    // JqFeatureExtractor with invalid JQ expression
    var cbrConfig =
        CbrConfig.builder()
            .feature("amount", ".transaction.amount")
            .feature("invalid", "this is not valid JQ !!!")
            .domain("test-domain")
            .topK(5)
            .minSimilarity(0.7)
            .build();

    var definition =
        CaseDefinition.builder()
            .namespace("test")
            .name("cbr-invalid-jq")
            .version("1.0")
            .cbrConfig(cbrConfig)
            .build();

    logRecords.clear(); // Clear any startup logs

    registry.registerCaseDefinition(definition);

    String formattedMessage =
        logRecords.isEmpty()
            ? ""
            : String.format(logRecords.get(0).getMessage(), logRecords.get(0).getParameters());

    assertThat(logRecords).isNotEmpty();
    assertThat(formattedMessage).contains("CbrConfig").contains("invalid");
  }

  @Test
  void registerCaseDefinition_validCbrConfigWithDomain_noWarning() {
    // Valid CbrConfig with domain
    var cbrConfig =
        CbrConfig.builder()
            .feature("amount", ".transaction.amount")
            .feature("type", ".transaction.type")
            .domain("test-domain")
            .topK(5)
            .minSimilarity(0.7)
            .build();

    var definition =
        CaseDefinition.builder()
            .namespace("test")
            .name("cbr-valid-domain")
            .version("1.0")
            .cbrConfig(cbrConfig)
            .build();

    registry.registerCaseDefinition(definition);

    assertThat(logRecords).noneMatch(r -> r.getMessage().contains("CbrConfig"));
  }

  @Test
  void registerCaseDefinition_cbrConfigWithNoDomainButWithEpisodicMemory_noWarning() {
    // CbrConfig with no domain, but EpisodicMemoryConfig present
    var cbrConfig =
        CbrConfig.builder()
            .feature("amount", ".transaction.amount")
            .topK(5)
            .minSimilarity(0.7)
            .build();

    var definition =
        CaseDefinition.builder()
            .namespace("test")
            .name("cbr-with-episodic")
            .version("1.0")
            .cbrConfig(cbrConfig)
            .episodicMemory("test-domain", ".entityId")
            .build();

    registry.registerCaseDefinition(definition);

    assertThat(logRecords).noneMatch(r -> r.getMessage().contains("CbrConfig"));
  }
}
