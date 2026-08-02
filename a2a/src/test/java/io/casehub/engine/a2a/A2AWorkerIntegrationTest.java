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
package io.casehub.engine.a2a;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import io.casehub.api.engine.CaseHub;
import io.casehub.api.model.Binding;
import io.casehub.api.model.CaseDefinition;
import io.casehub.api.model.CaseStatus;
import io.casehub.api.model.ContextChangeTrigger;
import io.casehub.api.model.Goal;
import io.casehub.api.model.GoalExpression;
import io.casehub.api.model.GoalKind;
import io.casehub.engine.common.spi.cache.CaseInstanceCache;
import io.casehub.worker.api.Capability;
import io.casehub.worker.api.Worker;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.common.QuarkusTestResourceLifecycleManager;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.junit.jupiter.api.Test;

@QuarkusTest
@QuarkusTestResource(
    value = A2AWorkerIntegrationTest.A2AServerResource.class,
    restrictToAnnotatedClass = true)
class A2AWorkerIntegrationTest {

  @Inject A2AIntegrationCaseHub caseHub;
  @Inject CaseInstanceCache caseInstanceCache;

  @Test
  void a2aWorkerExecutesAndCompletesCase() {
    A2AServerResource.server.enqueue(
        new MockResponse()
            .setBody(
                "{\"jsonrpc\":\"2.0\",\"id\":\"1\",\"result\":{\"id\":\"task-1\","
                    + "\"status\":{\"state\":\"completed\"},"
                    + "\"artifacts\":[{\"parts\":[{\"type\":\"text\","
                    + "\"text\":\"{\\\"analysisResult\\\":\\\"clean\\\"}\"}]}]}}")
            .addHeader("Content-Type", "application/json"));

    UUID caseId = caseHub.startCase(Map.of("status", "ready"));
    assertNotNull(caseId);

    await()
        .atMost(10, TimeUnit.SECONDS)
        .untilAsserted(
            () -> {
              var instance = caseInstanceCache.get(caseId);
              assertNotNull(instance, "instance should be in cache");
              assertEquals(
                  CaseStatus.COMPLETED,
                  instance.getState(),
                  "case should reach COMPLETED (was: " + instance.getState() + ")");
            });
  }

  @ApplicationScoped
  public static class A2AIntegrationCaseHub extends CaseHub {

    @ConfigProperty(name = "test.a2a.endpoint")
    String endpoint;

    @Override
    public CaseDefinition getDefinition() {
      Capability capability =
          Capability.builder()
              .name("analysis")
              .description("Analyse data")
              .inputSchema(".")
              .outputSchema(".")
              .build();

      Worker worker =
          Worker.builder()
              .name("remote-analyst")
              .capabilityName("analysis")
              .function(new A2AWorkerFunction(endpoint, null, false, A2AAuthConfig.NONE))
              .build();

      Binding binding =
          Binding.builder()
              .name("run-analysis")
              .capability(capability)
              .on(new ContextChangeTrigger(".status == \"ready\""))
              .build();

      Goal goal =
          Goal.builder()
              .name("done")
              .condition(".analysisResult != null")
              .kind(GoalKind.SUCCESS)
              .build();

      return CaseDefinition.builder()
          .namespace("test")
          .name("a2a-integration")
          .version("1.0.0")
          .title("A2A integration test")
          .capabilities(capability)
          .workers(worker)
          .bindings(binding)
          .goals(goal)
          .completion(GoalExpression.allOf(goal))
          .build();
    }
  }

  public static class A2AServerResource implements QuarkusTestResourceLifecycleManager {
    static MockWebServer server;

    @Override
    public Map<String, String> start() {
      server = new MockWebServer();
      try {
        server.start();
      } catch (IOException e) {
        throw new UncheckedIOException(e);
      }
      return Map.of("test.a2a.endpoint", server.url("/").toString());
    }

    @Override
    public void stop() {
      try {
        server.shutdown();
      } catch (Exception e) {
        // shutdown best-effort
      }
    }
  }
}
